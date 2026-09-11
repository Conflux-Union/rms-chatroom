package chatbridge

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strings"
)

// cryptor reproduces chatbridge/core/network/cryptor.py: AES-256-CBC with a
// fixed IV derived from the key, zero padding, and a hex-encoded ciphertext.
// The fixed IV makes encryption deterministic — that is what the protocol
// does; it is not a general-purpose cipher.
type cryptor struct {
	keyEmpty bool
	block    cipher.Block
	iv       []byte
}

func newCryptor(key string) *cryptor {
	if key == "" {
		return &cryptor{keyEmpty: true}
	}
	kb := pad16([]byte(key))
	hashed := sha256.Sum256(kb)
	block, err := aes.NewCipher(hashed[:]) // 32-byte key -> AES-256
	if err != nil {
		panic(fmt.Sprintf("chatbridge: aes.NewCipher: %v", err))
	}
	return &cryptor{block: block, iv: hashed[:aes.BlockSize]}
}

func pad16(b []byte) []byte {
	pad := (16 - len(b)%16) % 16
	if pad == 0 {
		return b
	}
	out := make([]byte, len(b)+pad)
	copy(out, b)
	return out
}

// encrypt returns the wire payload bytes for a packet JSON string.
func (c *cryptor) encrypt(text string) []byte {
	if c.keyEmpty {
		return []byte(text)
	}
	data := pad16([]byte(text))
	out := make([]byte, len(data))
	cipher.NewCBCEncrypter(c.block, c.iv).CryptBlocks(out, data)
	return []byte(hex.EncodeToString(out))
}

// decrypt reverses encrypt. Python's rstrip('\0') drops every trailing NUL,
// which only the padding introduced, so the JSON is recovered intact.
func (c *cryptor) decrypt(data []byte) (string, error) {
	if c.keyEmpty {
		return string(data), nil
	}
	raw, err := hex.DecodeString(string(data))
	if err != nil {
		return "", fmt.Errorf("chatbridge: ciphertext is not hex: %w", err)
	}
	if len(raw) == 0 || len(raw)%aes.BlockSize != 0 {
		return "", fmt.Errorf("chatbridge: ciphertext length %d is not a multiple of %d", len(raw), aes.BlockSize)
	}
	out := make([]byte, len(raw))
	cipher.NewCBCDecrypter(c.block, c.iv).CryptBlocks(out, raw)
	return strings.TrimRight(string(out), "\x00"), nil
}
