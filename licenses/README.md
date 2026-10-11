# 第三方许可证

本目录收录口袋酒馆分发的所有第三方组件的许可证文本,按组件归属分目录存放。构建时整个目录会被打进 APK 的 `assets/licenses/`,应用内可以查看。

| 目录 | 组件 | 版本 | 许可证 | 文本来源 |
|---|---|---|---|---|
| `sillytavern/` | SillyTavern(酒馆) | 1.19.0 | AGPL-3.0 | `kernel/LICENSE`(上游子模块) |
| `sillytavernpocket/` | 口袋酒馆(本项目) | — | AGPL-3.0 | 仓库根目录 `LICENSE` |
| `nodejs/` | Node.js 运行时,含其内置依赖(各依赖的许可证详见文本) | nodejs-lts 24.18.0 | MIT 及各内置依赖许可证 | Termux 包 `nodejs-lts` 的 copyright 文件 |
| `openssl/` | OpenSSL | 3.6.5 | Apache-2.0 | Termux `termux-licenses` 中的 Apache-2.0 文本 |
| `icu/` | ICU(国际化库) | 78.3 | Unicode License V3 | 上游 unicode-org/icu 的 `release-78.3` 标签 |
| `libcxx/` | libc++(C++ 标准库) | 30 | NCSA(Termux 包元数据标注) | Termux `termux-licenses` 中的 NCSA 文本 |
| `c-ares/` | c-ares(异步 DNS) | 1.34.8 | MIT | Termux 包 `c-ares` 的 copyright 文件 |
| `zlib/` | zlib | 1.3.2 | zlib License | Termux 包 `zlib` 的 copyright 文件 |
| `sqlite/` | SQLite | 3.54.0 | 公有领域 | Termux `termux-licenses` 中的 CC0 1.0 文本(Termux 将 Public Domain 映射到此) |
| `resolv-conf/` | resolv-conf | 1.3 | 公有领域 | 同上 |
| `ca-certificates/` | CA 证书数据 | 2026.09.25 | MPL-2.0 | Termux `termux-licenses` 中的 MPL-2.0 文本 |

## 说明

- **npm 依赖**:酒馆的生产依赖(约 667 个包)随 `kernel/node_modules/` 一并分发,每个包自带的 LICENSE 文件都保留在 APK 中。
- **已知待核对项**:`libcxx/` 的 NCSA 文本中仍有 `[year] [fullname]` 占位符,版权人信息需要以 LLVM 上游为准。
- **更新方式**:组件版本升级时,同步更新本表和对应目录的文本。
