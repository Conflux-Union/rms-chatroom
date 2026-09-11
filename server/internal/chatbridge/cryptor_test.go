package chatbridge

import "testing"

// Golden vectors generated with the reference Python implementation
// (chatbridge/core/network/cryptor.py, pycryptodome AES) so the Go port is
// byte-compatible with the ChatBridge v2 server.
func TestCryptorMatchesReferenceImplementation(t *testing.T) {
	cases := []struct {
		key       string
		plain     string
		cipherHex string
		roundtrip string
	}{
		{"ThisIstheSecret", `{"author": "Steve", "message": "hello 世界 <>[]{}"}`,
			"08512c9404ff5561eab602a80627ce185fa632f89ca76bafe238e1722212486ce9b360cf20c41097342565db0bdec4bc9dbdc8369d2008b4cb996d8a9ff94d36",
			`{"author": "Steve", "message": "hello 世界 <>[]{}"}`},
		{"ThisIstheSecret", `{"name": "RMS", "password": "pw123"}`,
			"c31aacd35c3ffba1d5ca5a628c63d9f3c54cca44f06ebe3f0758110bda526294ec134cb92c2ff7b51dfd69e4427b7f25",
			`{"name": "RMS", "password": "pw123"}`},
		// key shorter than 16 bytes -> zero padded before hashing
		{"k", `{"a": 1}`, "d5b8b475fd614af0418db176a8533ffc", `{"a": 1}`},
		// key exactly 16 bytes -> no padding
		{"0123456789abcdef", `{"a": 1}`, "f5dd4d5986f0b3dcad4a83e67a4d3779", `{"a": 1}`},
		// key exactly 32 bytes
		{"0123456789abcdef0123456789abcdef", `{"ping_type": "ping"}`,
			"0f5f780ac707323a0b6ec8b2c808d6ffe3370ffd76e4a2fdcc4f88b10e9f5a4b",
			`{"ping_type": "ping"}`},
		// plaintext exactly 16 bytes -> no extra zero block
		{"ThisIstheSecret", `{"a": 1}`, "181a05030a63c3dc42c473b45f376948", `{"a": 1}`},
		// plaintext 15 bytes -> zero padded to 16
		{"ThisIstheSecret", `{"a": 1,"b":2}`, "ed7458febf17fca3a994e61572e1132a", `{"a": 1,"b":2}`},
		// empty key -> plaintext passthrough
		{"", `{"x": "y"}`, `{"x": "y"}`, `{"x": "y"}`},
	}

	for _, tc := range cases {
		c := newCryptor(tc.key)
		got := string(c.encrypt(tc.plain))
		if got != tc.cipherHex {
			t.Errorf("encrypt(key=%q, plain=%q)\n got  %s\n want %s", tc.key, tc.plain, got, tc.cipherHex)
		}
		back, err := c.decrypt([]byte(tc.cipherHex))
		if err != nil {
			t.Errorf("decrypt(key=%q): %v", tc.key, err)
			continue
		}
		if back != tc.roundtrip {
			t.Errorf("decrypt(key=%q)\n got  %q\n want %q", tc.key, back, tc.roundtrip)
		}
	}
}

func TestCryptorRejectsMalformedCiphertext(t *testing.T) {
	c := newCryptor("ThisIstheSecret")
	for _, bad := range []string{"zz", "abcd", "0123456789abcdef0123456789abcde"} {
		if _, err := c.decrypt([]byte(bad)); err == nil {
			t.Errorf("decrypt(%q) expected error, got nil", bad)
		}
	}
}
