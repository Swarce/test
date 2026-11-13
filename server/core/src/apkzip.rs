//! Read one stored (uncompressed) entry from a ZIP/APK, no dependencies.
//!
//! The embedded host reads its bundled content straight out of the running APK, so it never writes
//! game data to disk. The patcher stores `assets/sgm-content.json` uncompressed, so no inflate is
//! needed here.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

const EOCD_SIG: u32 = 0x0605_4b50;
const CEN_SIG: u32 = 0x0201_4b50;
const LOC_SIG: u32 = 0x0403_4b50;

fn u16le(b: &[u8], o: usize) -> u16 {
    u16::from_le_bytes([b[o], b[o + 1]])
}
fn u32le(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

/// Return the uncompressed bytes of a **stored** entry named `want`, or None.
pub fn read_stored_entry(apk_path: &str, want: &str) -> Option<Vec<u8>> {
    let mut f = File::open(apk_path).ok()?;
    let len = f.seek(SeekFrom::End(0)).ok()?;
    // find the EOCD in the last 64 KiB + 22 bytes
    let scan = 66_000u64.min(len);
    f.seek(SeekFrom::Start(len - scan)).ok()?;
    let mut tail = vec![0u8; scan as usize];
    f.read_exact(&mut tail).ok()?;
    let mut eocd = None;
    let mut i = tail.len().saturating_sub(22);
    loop {
        if u32le(&tail, i) == EOCD_SIG {
            eocd = Some(i);
            break;
        }
        if i == 0 {
            break;
        }
        i -= 1;
    }
    let e = eocd?;
    let cd_size = u32le(&tail, e + 12) as u64;
    let cd_off = u32le(&tail, e + 16) as u64;
    // read the central directory
    f.seek(SeekFrom::Start(cd_off)).ok()?;
    let mut cd = vec![0u8; cd_size as usize];
    f.read_exact(&mut cd).ok()?;

    let mut p = 0usize;
    while p + 46 <= cd.len() && u32le(&cd, p) == CEN_SIG {
        let method = u16le(&cd, p + 10);
        let comp_size = u32le(&cd, p + 20) as usize;
        let name_len = u16le(&cd, p + 28) as usize;
        let extra_len = u16le(&cd, p + 30) as usize;
        let comment_len = u16le(&cd, p + 32) as usize;
        let local_off = u32le(&cd, p + 42) as u64;
        let name = String::from_utf8_lossy(&cd[p + 46..p + 46 + name_len]).into_owned();
        if name == want {
            if method != 0 {
                return None; // not stored; the patcher must store this entry
            }
            // read the local header to find the real data offset
            f.seek(SeekFrom::Start(local_off)).ok()?;
            let mut lh = [0u8; 30];
            f.read_exact(&mut lh).ok()?;
            if u32le(&lh, 0) != LOC_SIG {
                return None;
            }
            let ln = u16le(&lh, 26) as u64;
            let le = u16le(&lh, 28) as u64;
            f.seek(SeekFrom::Start(local_off + 30 + ln + le)).ok()?;
            let mut data = vec![0u8; comp_size];
            f.read_exact(&mut data).ok()?;
            return Some(data);
        }
        p += 46 + name_len + extra_len + comment_len;
    }
    None
}
