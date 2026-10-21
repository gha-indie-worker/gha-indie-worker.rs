import json
from pathlib import Path

root = Path(__file__).resolve().parent
source = (root / "stdlib" / "compute.ores").read_text(encoding="utf-8")
contract = json.loads((root / "contract" / "portable-compute-stdlib.json").read_text(encoding="utf-8"))

assert "define module compute" in source
for type_name in ("DeviceCapabilities", "BufferLayout", "DispatchShape", "KernelRequirements"):
    assert type_name in source
for forbidden in ("java:", "do java", "ServiceLoader"):
    assert forbidden not in source

assert contract["namespace"] == "std/compute"
assert contract["providerDiscovery"] == "explicit-registration"
assert contract["implicitTransfers"] is False
assert contract["implicitCpuFallback"] is False
assert contract["runtimeReflectionRequired"] is False
assert contract["cancellationImpliesQuiescence"] is False
assert contract["descriptorIntegerDomain"] == "signed-i64"
assert contract["descriptorValidationScope"] == "portable-structural"
assert contract["providerValidationMayBeStricter"] is True
assert contract["checkedByteArithmeticBeforeAllocationOrTransfer"] is True
assert set(contract["sealedRuntimeHandles"]) == {"Device", "Buffer<T>", "Fence", "KernelContext"}

print("portable-compute-contract-hardening-ok")
