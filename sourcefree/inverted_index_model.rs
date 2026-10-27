#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Handle {
    owner: u64,
    slot: usize,
    generation: u64,
}

#[derive(Clone, Copy)]
struct Slot {
    row: usize,
    generation: u64,
    live: bool,
}

struct Rows {
    owner: u64,
    rows: Vec<usize>,
    slots: Vec<Slot>,
    free: Vec<usize>,
    closed: bool,
}

impl Rows {
    fn new(owner: u64) -> Self {
        Self {
            owner,
            rows: Vec::new(),
            slots: Vec::new(),
            free: Vec::new(),
            closed: false,
        }
    }

    fn push(&mut self) -> Result<Handle, &'static str> {
        if self.closed {
            return Err("closed");
        }
        if self.rows.len() >= i32::MAX as usize {
            return Err("row-count");
        }
        let row = self.rows.len();
        let slot = if let Some(slot) = self.free.pop() {
            slot
        } else {
            self.slots.push(Slot {
                row: 0,
                generation: 1,
                live: false,
            });
            self.slots.len() - 1
        };
        self.slots[slot].row = row;
        self.slots[slot].live = true;
        self.rows.push(slot);
        Ok(self.issue(slot))
    }

    fn index(&self, value: i64) -> Result<Handle, &'static str> {
        if self.closed {
            return Err("closed");
        }
        if value < 0 || value > i32::MAX as i64 {
            return Err("bounds");
        }
        let row = value as usize;
        if row >= self.rows.len() {
            return Err("bounds");
        }
        let slot = self.rows[row];
        let state = self.slots.get(slot).ok_or("corrupt")?;
        if !state.live || state.row != row {
            return Err("corrupt");
        }
        Ok(self.issue(slot))
    }

    fn resolve(&self, handle: Handle) -> Result<usize, &'static str> {
        if self.closed {
            return Err("closed");
        }
        if handle.owner != self.owner {
            return Err("foreign");
        }
        let state = self.slots.get(handle.slot).ok_or("foreign")?;
        if !state.live || state.generation != handle.generation {
            return Err("stale");
        }
        Ok(state.row)
    }

    fn erase_swap(&mut self, handle: Handle) -> Result<(), &'static str> {
        let row = self.resolve(handle)?;
        let last = self.rows.len() - 1;
        if row != last {
            let moved = self.rows[last];
            self.rows[row] = moved;
            self.slots[moved].row = row;
        }
        self.rows.pop();

        let state = &mut self.slots[handle.slot];
        state.live = false;
        if state.generation != u64::MAX {
            state.generation += 1;
            self.free.push(handle.slot);
        }
        Ok(())
    }

    fn issue(&self, slot: usize) -> Handle {
        Handle {
            owner: self.owner,
            slot,
            generation: self.slots[slot].generation,
        }
    }

    fn close(&mut self) {
        self.closed = true;
        for slot in &mut self.slots {
            slot.live = false;
        }
    }
}

#[test]
fn bracket_bounds_swap_delete_and_aba() {
    let mut rows = Rows::new(1);
    for bad in [i64::MIN, -1, i32::MAX as i64 + 1, i64::MAX] {
        assert_eq!(rows.index(bad), Err("bounds"));
    }
    assert_eq!(rows.index(0), Err("bounds"));

    let first = rows.push().unwrap();
    let middle = rows.push().unwrap();
    let last = rows.push().unwrap();
    let saved = rows.index(1).unwrap();
    assert_eq!(saved, middle);
    assert_eq!(rows.index(3), Err("bounds"));

    rows.erase_swap(first).unwrap();
    assert_eq!(rows.resolve(last), Ok(0));
    assert_eq!(rows.resolve(saved), Ok(1));
    assert_eq!(rows.index(0).unwrap().slot, last.slot);

    rows.erase_swap(saved).unwrap();
    assert_eq!(rows.resolve(saved), Err("stale"));
    let reused = rows.push().unwrap();
    assert_eq!(reused.slot, middle.slot);
    assert_ne!(reused.generation, middle.generation);
    assert_eq!(rows.resolve(saved), Err("stale"));
}

#[test]
fn foreign_owner_and_close_are_fenced() {
    let mut a = Rows::new(10);
    let mut b = Rows::new(20);
    let a0 = a.push().unwrap();
    let b0 = b.push().unwrap();
    assert_eq!(a.resolve(b0), Err("foreign"));
    assert_eq!(b.resolve(a0), Err("foreign"));
    a.close();
    assert_eq!(a.resolve(a0), Err("closed"));
    assert_eq!(a.index(0), Err("closed"));
}
