"""Checked NdArray layout/borrow reference for compiler ABI negotiation.

Not an Oreslang implementation: backend lowering must independently prove its
owned contiguous storage, view lifetimes, and actor message transfer semantics.
"""
from dataclasses import dataclass

LIMIT = (1 << 63) - 1


class LayoutError(ValueError):
    pass


def _bounded_int(value, minimum, maximum, label):
    if type(value) is not int or not minimum <= value <= maximum:
        raise LayoutError(f"invalid {label}")
    return value


def checked_elements(shape):
    if type(shape) not in (tuple, list) or len(shape) > 16:
        raise LayoutError("rank outside supported reference bounds")
    for dim in shape:
        _bounded_int(dim, 0, LIMIT, "dimension")
    if 0 in shape:
        return 0
    total = 1
    for dim in shape:
        if dim and total > LIMIT // dim:
            raise LayoutError("element-count overflow")
        total *= dim
    return total


def contiguous_bytes(shape, itemsize):
    itemsize = _bounded_int(itemsize, 1, 64, "itemsize")
    elements = checked_elements(shape)
    if elements > LIMIT // itemsize:
        raise LayoutError("byte-extent overflow")
    return elements * itemsize


def validate_view(shape, strides_bytes, offset_bytes, storage_bytes, itemsize):
    """Conservatively bound the byte range touched by a strided view.

    Even a zero-element view needs a valid owner-bound offset. No source
    pointer or allocation occurs in this reference implementation.
    """
    _bounded_int(itemsize, 1, 64, "itemsize")
    if type(shape) not in (tuple, list) or len(shape) > 16:
        raise LayoutError("invalid view shape")
    _bounded_int(storage_bytes, 0, LIMIT, "storage_bytes")
    _bounded_int(offset_bytes, 0, LIMIT, "offset_bytes")
    if type(strides_bytes) not in (tuple, list) or len(strides_bytes) != len(shape):
        raise LayoutError("strides/rank mismatch")
    elements = checked_elements(shape)
    for stride in strides_bytes:
        _bounded_int(stride, -LIMIT, LIMIT, "stride")
        if stride % itemsize:
            raise LayoutError("unaligned element stride")
    if offset_bytes % itemsize:
        raise LayoutError("unaligned view offset")
    if elements == 0:
        if offset_bytes > storage_bytes:
            raise LayoutError("empty view offset outside owner")
        return offset_bytes, offset_bytes
    low = high = offset_bytes
    for dim, stride in zip(shape, strides_bytes):
        extent = (dim - 1) * stride
        low += min(0, extent)
        high += max(0, extent)
    high += itemsize
    if low < 0 or high > storage_bytes or high > LIMIT:
        raise LayoutError("view escapes owned backing storage")
    return low, high


def validate_unique_write_view(shape, strides_bytes, offset_bytes,
                               storage_bytes, itemsize):
    """Sufficient, deliberately conservative proof of non-aliasing layout.

    Sort axes by absolute stride. Each outer axis must be separated by
    at least the entire footprint of all faster-changing axes. This may
    reject unusual safe layouts; it must never admit a broadcast writer.
    """
    start, stop = validate_view(shape, strides_bytes, offset_bytes,
                                storage_bytes, itemsize)
    if checked_elements(shape) == 0:
        return start, stop
    span = itemsize
    axes = sorted((abs(stride), dim) for dim, stride in
                  zip(shape, strides_bytes) if dim > 1)
    for stride, dim in axes:
        if stride < span:
            raise LayoutError("mutable view has overlapping element addresses")
        span += (dim - 1) * stride
        if span > LIMIT:
            raise LayoutError("mutable view address-span overflow")
    return start, stop


@dataclass(frozen=True, eq=False)
class Lease:
    owner: str
    generation: int
    mutable: bool
    start: int
    end: int
    token: int


class BorrowBook:
    def __init__(self):
        self._generations = {}
        self._last_generation = {}
        self._leases = set()
        self._next_token = 0

    def register(self, owner, generation):
        if type(owner) is not str or not owner or type(generation) is not int or generation < 1:
            raise LayoutError("invalid owner generation")
        if owner in self._generations:
            raise LayoutError("owner already registered")
        if generation <= self._last_generation.get(owner, 0):
            raise LayoutError("owner generation must increase monotonically")
        self._last_generation[owner] = generation
        self._generations[owner] = generation

    def borrow(self, owner, generation, *, mutable, start, end):
        if type(owner) is not str or self._generations.get(owner) != generation or type(generation) is not int:
            raise LayoutError("stale or alien owner")
        if type(mutable) is not bool:
            raise LayoutError("mutable authorization must be boolean")
        _bounded_int(start, 0, LIMIT, "start")
        _bounded_int(end, start, LIMIT, "end")
        for held in self._leases:
            if (held.owner == owner and held.generation == generation
                    and max(start, held.start) < min(end, held.end)
                    and (held.mutable or mutable)):
                raise LayoutError("overlapping mutable loan")
        self._next_token += 1
        candidate = Lease(owner, generation, mutable, start, end, self._next_token)
        self._leases.add(candidate)
        return candidate

    def borrow_view(self, owner, generation, *, mutable, shape, strides_bytes,
                    offset_bytes, storage_bytes, itemsize):
        """Admit a typed view only after exact storage extent and alias checks.

        Caller must provide the owner's REAL validated storage extent;
        the reference book does not authenticate a backend buffer.
        """
        if type(mutable) is not bool:
            raise LayoutError("mutable authorization must be boolean")
        check = validate_unique_write_view if mutable else validate_view
        start, stop = check(shape, strides_bytes, offset_bytes,
                            storage_bytes, itemsize)
        return self.borrow(owner, generation, mutable=mutable, start=start, end=stop)

    def release(self, lease):
        if type(lease) is not Lease or lease not in self._leases:
            raise LayoutError("unknown or already released loan")
        self._leases.remove(lease)

    def drop_owner(self, owner, generation):
        if type(owner) is not str or type(generation) is not int or self._generations.get(owner) != generation:
            raise LayoutError("stale owner generation")
        if any(loan.owner == owner for loan in self._leases):
            raise LayoutError("cannot drop owner with live views")
        del self._generations[owner]
