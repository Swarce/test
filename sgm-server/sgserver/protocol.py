"""Wire-protocol details shared with the game client (ClientAPI / NetworkRequest / HttpManager).

All values here were recovered from the 8.11.0 Android client:
  * every endpoint lives under /v1/ and bodies are MiniJSON objects
  * a handful of fight endpoints carry an X-SG-SIG header (request and response)
  * some endpoints expect a Base64-encoded response body (HttpEncoding.Base64)
"""
import base64
import hashlib
import json

API_PREFIX = '/v1/'

# ClientAPI.signatureSecret (set in ClientAPI..cctor)
SIGNATURE_SECRET = 'k5fh3Xx+Dk{;h*YR]pHRs-$ya*XeaT)Z[ukdf-7Vk]vb53S;L<992+t%)SCpP[C,'

SIG_HEADER = 'X-SG-SIG'
TRANSACTION_HEADER = 'X-SG-TRANS-ID'
SESSION_COOKIE = 'sgsession'


def sha256_hex(data: bytes) -> str:
    # ByteUtil.ToHex formats each byte with "X2" (upper case)
    return hashlib.sha256(data).hexdigest().upper()


def request_signature(endpoint: str, queries, body: str) -> str:
    """NetworkRequest.GenerateRequestSignature(endpoint, queries, body).

    endpoint is relative to /v1/ (e.g. "fight/abc/start"); queries are "key=value"
    strings, sorted with String.Compare before being appended.
    """
    s = API_PREFIX + endpoint if endpoint else ''
    if queries:
        for q in sorted(queries, key=_dotnet_compare_key):
            s += q
    data = s.encode('utf-8')
    if body is not None:
        data += body.encode('utf-8')
    return sha256_hex(data + SIGNATURE_SECRET.encode('utf-8'))


def response_signature(body_text: str) -> str:
    """NetworkRequest.HasValidResponseSignature recomputes the signature with no endpoint and
    no queries over the (decoded) response body; the comparison is case-insensitive."""
    return request_signature('', None, body_text)


def _dotnet_compare_key(s: str):
    # String.Compare(a, b) is culture-aware; for the ASCII "key=value" strings used here a
    # case-insensitive-first ordering matches it closely enough.
    return (s.lower(), s)


def dumps(obj) -> str:
    """Serialize for MiniJSON: plain JSON, no NaN, compact."""
    return json.dumps(obj, separators=(',', ':'), ensure_ascii=False, allow_nan=False)


def encode_body(text: str, b64: bool) -> bytes:
    if b64:
        return base64.b64encode(text.encode('utf-8'))
    return text.encode('utf-8')


class ApiError(Exception):
    """Raised by handlers; rendered as {"errorCode", "message"} which NetworkRequest.FinishRequest reads."""

    def __init__(self, status: int, code: str, message: str = ''):
        super().__init__(message or code)
        self.status = status
        self.code = code
        self.message = message or code
