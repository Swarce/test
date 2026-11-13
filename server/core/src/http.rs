//! A small HTTP/1.1 server on std::net (no dependencies).
//!
//! Enough for the game client: GET/POST/PUT/DELETE, keep-alive, `Expect: 100-continue`, a `Date`
//! header on every response (the client syncs its clock from it), and arbitrary request/response
//! bodies. One thread per connection.

use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

pub struct Request {
    pub method: String,
    pub target: String, // path?query
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }
}

pub struct Response {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Response {
    pub fn new(status: u16) -> Self {
        Response { status, headers: Vec::new(), body: Vec::new() }
    }
    pub fn header(mut self, k: &str, v: &str) -> Self {
        self.headers.push((k.into(), v.into()));
        self
    }
    pub fn body(mut self, b: Vec<u8>) -> Self {
        self.body = b;
        self
    }
}

pub trait Handler: Send + Sync + 'static {
    fn handle(&self, req: &Request) -> Response;
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        204 => "No Content",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        500 => "Internal Server Error",
        _ => "OK",
    }
}

/// RFC 1123 date in GMT, e.g. "Sun, 06 Nov 1994 08:49:37 GMT". The client only parses it to set its
/// clock, so it must be a valid HTTP-date for the current time.
pub fn http_date(now_secs: u64) -> String {
    const DAYS: [&str; 7] = ["Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed"]; // 1970-01-01 = Thursday
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let days = now_secs / 86400;
    let secs = now_secs % 86400;
    let (h, mi, s) = (secs / 3600, (secs % 3600) / 60, secs % 60);
    let dow = DAYS[(days % 7) as usize];
    // civil-from-days (Howard Hinnant's algorithm)
    let z = days as i64 + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!(
        "{}, {:02} {} {:04} {:02}:{:02}:{:02} GMT",
        dow, d, MONTHS[(m - 1) as usize], y, h, mi, s
    )
}

pub fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// Serve forever on `addr` (e.g. "127.0.0.1:47820"). Returns the bound address on success.
pub fn serve(addr: &str, handler: Arc<dyn Handler>) -> std::io::Result<()> {
    let listener = TcpListener::bind(addr)?;
    for stream in listener.incoming() {
        let stream = match stream {
            Ok(s) => s,
            Err(_) => continue,
        };
        let h = handler.clone();
        std::thread::spawn(move || {
            let _ = handle_conn(stream, h);
        });
    }
    Ok(())
}

/// Bind without serving (so the caller can report the real port, then serve on a thread).
pub fn bind(addr: &str) -> std::io::Result<TcpListener> {
    TcpListener::bind(addr)
}

pub fn serve_listener(listener: TcpListener, handler: Arc<dyn Handler>) {
    for stream in listener.incoming() {
        if let Ok(stream) = stream {
            let h = handler.clone();
            std::thread::spawn(move || {
                let _ = handle_conn(stream, h);
            });
        }
    }
}

fn handle_conn(stream: TcpStream, handler: Arc<dyn Handler>) -> std::io::Result<()> {
    stream.set_nodelay(true).ok();
    let mut writer = stream.try_clone()?;
    let mut reader = BufReader::new(stream);
    loop {
        let req = match read_request(&mut reader, &mut writer)? {
            Some(r) => r,
            None => return Ok(()), // connection closed
        };
        let keep_alive = req
            .header("Connection")
            .map(|c| !c.eq_ignore_ascii_case("close"))
            .unwrap_or(true);
        let is_head = req.method.eq_ignore_ascii_case("HEAD");
        let resp = handler.handle(&req);
        write_response(&mut writer, &resp, keep_alive, is_head)?;
        if !keep_alive {
            return Ok(());
        }
    }
}

fn read_line(reader: &mut impl BufRead) -> std::io::Result<Option<String>> {
    let mut line = Vec::new();
    let n = read_until_crlf(reader, &mut line)?;
    if n == 0 {
        return Ok(None);
    }
    Ok(Some(String::from_utf8_lossy(&line).trim_end().to_string()))
}

fn read_until_crlf(reader: &mut impl BufRead, out: &mut Vec<u8>) -> std::io::Result<usize> {
    let n = reader.read_until(b'\n', out)?;
    Ok(n)
}

fn read_request(
    reader: &mut BufReader<TcpStream>,
    writer: &mut TcpStream,
) -> std::io::Result<Option<Request>> {
    let start = match read_line(reader)? {
        Some(l) if !l.is_empty() => l,
        Some(_) => return Ok(None),
        None => return Ok(None),
    };
    let mut parts = start.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("").to_string();
    if method.is_empty() || target.is_empty() {
        return Ok(None);
    }
    let mut headers = Vec::new();
    loop {
        match read_line(reader)? {
            Some(l) if l.is_empty() => break,
            Some(l) => {
                if let Some(idx) = l.find(':') {
                    headers.push((l[..idx].trim().to_string(), l[idx + 1..].trim().to_string()));
                }
            }
            None => return Ok(None),
        }
    }
    let content_length: usize = headers
        .iter()
        .find(|(k, _)| k.eq_ignore_ascii_case("Content-Length"))
        .and_then(|(_, v)| v.parse().ok())
        .unwrap_or(0);
    // Expect: 100-continue -> acknowledge before reading the body.
    if headers
        .iter()
        .any(|(k, v)| k.eq_ignore_ascii_case("Expect") && v.eq_ignore_ascii_case("100-continue"))
    {
        writer.write_all(b"HTTP/1.1 100 Continue\r\n\r\n")?;
        writer.flush()?;
    }
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        reader.read_exact(&mut body)?;
    }
    Ok(Some(Request { method, target, headers, body }))
}

fn write_response(
    writer: &mut TcpStream,
    resp: &Response,
    keep_alive: bool,
    is_head: bool,
) -> std::io::Result<()> {
    let mut head = format!("HTTP/1.1 {} {}\r\n", resp.status, reason(resp.status));
    let mut have_date = false;
    let mut have_ctype = false;
    for (k, v) in &resp.headers {
        if k.eq_ignore_ascii_case("Date") {
            have_date = true;
        }
        if k.eq_ignore_ascii_case("Content-Type") {
            have_ctype = true;
        }
        head.push_str(&format!("{}: {}\r\n", k, v));
    }
    if !have_date {
        head.push_str(&format!("Date: {}\r\n", http_date(now_secs())));
    }
    if !have_ctype {
        head.push_str("Content-Type: application/json; charset=utf-8\r\n");
    }
    head.push_str(&format!("Content-Length: {}\r\n", resp.body.len()));
    head.push_str(if keep_alive { "Connection: keep-alive\r\n" } else { "Connection: close\r\n" });
    head.push_str("\r\n");
    writer.write_all(head.as_bytes())?;
    if !is_head {
        writer.write_all(&resp.body)?;
    }
    writer.flush()
}
