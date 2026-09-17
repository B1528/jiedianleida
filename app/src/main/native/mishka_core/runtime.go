package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"flag"
	"fmt"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"unsafe"
	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/component/updater"
	"github.com/metacubex/mihomo/config"
	Const "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/log"
)

//export mihomoEntry
//
// dlopen + dlsym 进入点：解析 -d / -f / --override-json / --secret / --ext-ctl，启动 hub 后阻塞等信号。
// argv[0] 透传 binary 路径作占位；返回值即进程退出码。
func mihomoEntry(argc C.int, argv **C.char) C.int {
	args := make([]string, int(argc))
	if argc > 0 && argv != nil {
		arr := unsafe.Slice(argv, int(argc))
		for i, p := range arr {
			args[i] = C.GoString(p)
		}
	}
	os.Args = args
	return C.int(runMihomo())
}

func runMihomo() int {
	// 独立 FlagSet 避开 mishka_core 其他文件可能注册到 flag.CommandLine 的 flag。
	fs := flag.NewFlagSet("mihomo", flag.ExitOnError)
	var (
		homeDir            string
		configFile         string
		secret             string
		externalController string
		overrideJSON       string
		ageSecretKey       string
	)
	fs.StringVar(&homeDir, "d", "", "set configuration directory")
	fs.StringVar(&configFile, "f", "", "specify configuration file")
	fs.StringVar(&overrideJSON, "override-json", "", "path to a JSON file whose fields override the parsed RawConfig")
	fs.StringVar(&secret, "secret", "", "override RESTful API secret")
	fs.StringVar(&externalController, "ext-ctl", "", "override external controller address")
	fs.StringVar(&ageSecretKey, "age-secret-key", "", "age secret key to decrypt age-armor encrypted configuration")
	if err := fs.Parse(os.Args[1:]); err != nil {
		return 2
	}

	// 任何意外走系统 resolver 的代码路径立刻自爆，方便定位（mihomo 应当全程用自己的 DNS 栈）。
	net.DefaultResolver.PreferGo = true
	net.DefaultResolver.Dial = func(ctx context.Context, network, address string) (net.Conn, error) {
		fmt.Fprintln(os.Stderr, "panic: net.DefaultResolver.Dial should never be called")
		os.Exit(2)
		return nil, nil
	}

	if overrideJSON != "" {
		config.OverrideJSONPath = overrideJSON
	}

	// age armor 加密的订阅配置在磁盘上保持加密，运行时用此密钥解密（hub.Parse 内部调
	// config.UnmarshalRawConfig → age.DecryptBytes 读全局密钥）；SIGHUP reload 也持续生效。
	if ageSecretKey != "" {
		age.SetGlobalSecretKeys(ageSecretKey)
	}

	if homeDir != "" {
		if !filepath.IsAbs(homeDir) {
			cwd, _ := os.Getwd()
			homeDir = filepath.Join(cwd, homeDir)
		}
		Const.SetHomeDir(homeDir)
	}

	if configFile == "" {
		configFile = filepath.Join(Const.Path.HomeDir(), Const.Path.Config())
	} else if !filepath.IsAbs(configFile) {
		cwd, _ := os.Getwd()
		configFile = filepath.Join(cwd, configFile)
	}
	Const.SetConfig(configFile)

	if err := config.Init(Const.Path.HomeDir()); err != nil {
		log.Fatalln("init config dir: %s", err.Error())
	}

	configBytes, err := os.ReadFile(configFile)
	if err != nil {
		log.Fatalln("read config: %s", err.Error())
	}

	var options []hub.Option
	if externalController != "" {
		options = append(options, hub.WithExternalController(externalController))
	}
	if secret != "" {
		options = append(options, hub.WithSecret(secret))
	}

	if err := hub.Parse(configBytes, options...); err != nil {
		log.Fatalln("Parse config: %s", err.Error())
	}

	if updater.GeoAutoUpdate() {
		updater.RegisterGeoUpdater()
	}

	defer executor.Shutdown()

	termSig := make(chan os.Signal, 1)
	hupSig := make(chan os.Signal, 1)
	signal.Notify(termSig, syscall.SIGINT, syscall.SIGTERM)
	signal.Notify(hupSig, syscall.SIGHUP)

	for {
		select {
		case <-termSig:
			return 0
		case <-hupSig:
			if err := hub.Parse(configBytes, options...); err != nil {
				log.Errorln("Reload config: %s", err.Error())
			}
		}
	}
}
