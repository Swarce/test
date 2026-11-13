//! A small non-cryptographic PRNG for server-side ids and loot rolls.
//!
//! Not for security. Seeded from the system clock and a per-instance counter so ids are unique
//! within a database; loot rolls vary per call like the Python server's `random`.

use std::time::{SystemTime, UNIX_EPOCH};

pub struct Rng {
    state: u64,
}

impl Default for Rng {
    fn default() -> Self {
        Rng::new()
    }
}

impl Rng {
    pub fn new() -> Rng {
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0x9e3779b97f4a7c15);
        let addr = &nanos as *const u64 as u64;
        Rng { state: nanos ^ addr.rotate_left(32) ^ 0x2545F4914F6CDD1D }
    }

    #[inline]
    fn next_u64(&mut self) -> u64 {
        // xorshift64*
        let mut x = self.state;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.state = x;
        x.wrapping_mul(0x2545F4914F6CDD1D)
    }

    /// 32 lowercase hex characters (uuid4().hex-shaped).
    pub fn hex32(&mut self) -> String {
        let a = self.next_u64();
        let b = self.next_u64();
        format!("{:016x}{:016x}", a, b)
    }

    /// Integer in [lo, hi).
    pub fn range(&mut self, lo: i64, hi: i64) -> i64 {
        if hi <= lo {
            return lo;
        }
        lo + (self.next_u64() % ((hi - lo) as u64)) as i64
    }

    /// Float in [0, top).
    pub fn uniform(&mut self, top: f64) -> f64 {
        (self.next_u64() as f64 / u64::MAX as f64) * top
    }

    /// Float in [0, 1).
    pub fn unit(&mut self) -> f64 {
        self.next_u64() as f64 / (u64::MAX as f64 + 1.0)
    }
}
