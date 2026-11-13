//! Wire protocol shared with the game client: signatures, Base64, the error envelope.
//!
//! Ported from `reference-server/sgserver/protocol.py`; the constants and algorithms come from the
//! 8.11.0/8.12.0 client (`ClientAPI`, `NetworkRequest`).

use crate::sha256::sha256;

pub const API_PREFIX: &str = "/v1/";
pub const SIG_HEADER: &str = "X-SG-SIG";
pub const TRANSACTION_HEADER: &str = "X-SG-TRANS-ID";
pub const SESSION_COOKIE: &str = "sgsession";

/// `ClientAPI.signatureSecret`.
pub const SIGNATURE_SECRET: &[u8] =
    b"k5fh3Xx+Dk{;h*YR]pHRs-$ya*XeaT)Z[ukdf-7Vk]vb53S;L<992+t%)SCpP[C,";

fn hex_upper(bytes: &[u8]) -> String {
    const H: &[u8; 16] = b"0123456789ABCDEF";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(H[(b >> 4) as usize] as char);
        s.push(H[(b & 0xf) as usize] as char);
    }
    s
}

/// `NetworkRequest.GenerateRequestSignature`: SHA-256 over `/v1/<endpoint>` + sorted queries + body
/// + secret, upper-case hex. `queries` are already `"key=value"` strings.
pub fn request_signature(endpoint: &str, queries: &mut [String], body: &str) -> String {
    let mut s = String::new();
    if !endpoint.is_empty() {
        s.push_str(API_PREFIX);
        s.push_str(endpoint);
    }
    // String.Compare ordering: for the ASCII "key=value" strings here, case-insensitive-first.
    queries.sort_by(|a, b| {
        let (la, lb) = (a.to_lowercase(), b.to_lowercase());
        la.cmp(&lb).then_with(|| a.cmp(b))
    });
    for q in queries.iter() {
        s.push_str(q);
    }
    let mut data = s.into_bytes();
    data.extend_from_slice(body.as_bytes());
    data.extend_from_slice(SIGNATURE_SECRET);
    hex_upper(&sha256(&data))
}

/// `NetworkRequest.HasValidResponseSignature`: signature with no endpoint/queries over the body.
pub fn response_signature(body: &str) -> String {
    request_signature("", &mut [], body)
}

/// An API error rendered as `{"errorCode","message"}` with an HTTP status.
#[derive(Debug, Clone)]
pub struct ApiError {
    pub status: u16,
    pub code: String,
    pub message: String,
}

impl ApiError {
    pub fn new(status: u16, code: &str, message: &str) -> Self {
        ApiError { status, code: code.into(), message: message.into() }
    }
    pub fn unauthorized(msg: &str) -> Self {
        ApiError::new(401, "Unauthorized", msg)
    }
    pub fn bad_request(msg: &str) -> Self {
        ApiError::new(400, "BadRequest", msg)
    }
    pub fn not_found(msg: &str) -> Self {
        ApiError::new(404, "NotFound", msg)
    }
}
