//go:build android

package tun

import (
	"syscall"
	"unsafe"
)

// Read reads from TUN file descriptor
func (t *tunFile) Read(p []byte) (n int, err error) {
	n, err = syscall.Read(t.fd, p)
	return
}

// Write writes to TUN file descriptor
func (t *tunFile) Write(p []byte) (n int, err error) {
	n, err = syscall.Write(t.fd, p)
	return
}

// Protect protects a socket file descriptor from VPN routing
func Protect(fd int, protectFn func(int) bool) bool {
	if protectFn != nil {
		return protectFn(fd)
	}
	return false
}

// SetNonblock sets a file descriptor to non-blocking mode
func SetNonblock(fd int, nonblocking bool) error {
	return syscall.SetNonblock(fd, nonblocking)
}

// GetSockOpt gets socket option
func GetSockOpt(fd, level, opt int) (int, error) {
	var value int
	vallen := uint32(unsafe.Sizeof(value))
	_, _, errno := syscall.Syscall6(
		syscall.SYS_GETSOCKOPT,
		uintptr(fd),
		uintptr(level),
		uintptr(opt),
		uintptr(unsafe.Pointer(&value)),
		uintptr(unsafe.Pointer(&vallen)),
		0,
	)
	if errno != 0 {
		return 0, errno
	}
	return value, nil
}
