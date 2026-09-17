package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"unsafe"
	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/component/http"
	"github.com/metacubex/mihomo/constant"
)

// panic 逸出 //export 边界会终止宿主进程——JNI 是 in-process，死的是整个 app。
// 这里收口成 "error: " 协议；只覆盖同一 goroutine，fatal error 无解。
func guardString(fn func() string) (ret *C.char) {
	defer func() {
		if r := recover(); r != nil {
			ret = C.CString(fmt.Sprintf("error: panic: %v", r))
		}
	}()
	return C.CString(fn())
}

var (
	cancelRegistry  sync.Map // map[int32]func()
	progressStore   sync.Map // map[int32]string
	initOnce        sync.Once
	mishkaUserAgent atomic.Value
)

//export mishkaCoreInit
func mishkaCoreInit(homeDir *C.char, userAgent *C.char) {
	// homeDir 决定 mihomo 读 GeoIP/GeoSite/ASN 数据库的位置；进程级全局，只设一次。
	initOnce.Do(func() {
		constant.SetHomeDir(C.GoString(homeDir))
	})
	ua := C.GoString(userAgent)
	if ua != "" {
		mishkaUserAgent.Store(ua)
		http.SetUA(ua)
	}
}

//export mishkaFreeString
//
// Go 通过 C.CString 分配并返回的 C 字符串必须由 Go 释放，C 侧调 free() 会破坏 cgo runtime 堆。
func mishkaFreeString(s *C.char) {
	if s != nil {
		C.free(unsafe.Pointer(s))
	}
}

//export mishkaCancel
func mishkaCancel(token C.int) {
	if v, ok := cancelRegistry.Load(int32(token)); ok {
		if cancel, ok := v.(func()); ok {
			cancel()
		}
	}
}

//export mishkaQueryProgress
func mishkaQueryProgress(token C.int) *C.char {
	if v, ok := progressStore.Load(int32(token)); ok {
		if s, ok := v.(string); ok {
			return C.CString(s)
		}
	}
	return nil
}

//export mishkaSetAgeSecretKey
//
// 设置进程级 age 解密密钥，供订阅导入解密 age armor 加密的配置；传空字符串清除。
// fetchAndValid 由 processLock 串行执行，调用方在 fetch 前设置、fetch 后清空，互不污染。
func mishkaSetAgeSecretKey(cKey *C.char) {
	key := strings.TrimSpace(C.GoString(cKey))
	if key == "" {
		age.SetGlobalSecretKeys()
	} else {
		age.SetGlobalSecretKeys(key)
	}
}

//export mishkaGenAgeKeyPair
//
// 生成 x25519 age 密钥对，返回 "secretKey\npublicKey"；失败返回 "error: ..."。
// 调用方必须 mishkaFreeString 释放返回值。
func mishkaGenAgeKeyPair() *C.char {
	return guardString(func() string {
		sk, pk, err := age.GenX25519KeyPair()
		if err != nil {
			return "error: " + err.Error()
		}
		return sk + "\n" + pk
	})
}

//export mishkaGenAgeHybridKeyPair
//
// 生成 mlkem768-x25519 抗量子 age 密钥对，返回 "secretKey\npublicKey"；失败返回 "error: ..."。
// 调用方必须 mishkaFreeString 释放返回值。
func mishkaGenAgeHybridKeyPair() *C.char {
	return guardString(func() string {
		sk, pk, err := age.GenHybridKeyPair()
		if err != nil {
			return "error: " + err.Error()
		}
		return sk + "\n" + pk
	})
}

func main() {}
