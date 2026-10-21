# release

**构建产物不放进仓库**，走 [GitHub Releases](https://github.com/zsjs110110-cpu/TACZ-Legacy-Next/releases) 附件下载。

| 版本 | 大小 | SHA256 | 下载 |
|---|---|---|---|
| `0.1.1` | 52 770 700 B | `017470E2078EAB45BA59E77F5A4F04B07BAD9F3ED5B13B3C0C2A9AFDFA937730` | [Release v0.1.1](https://github.com/zsjs110110-cpu/TACZ-Legacy-Next/releases/tag/v0.1.1) |

为什么不在仓库里放 jar：单个 jar 约 50 MB，留在 Git 历史里会让每次 `git clone` 都多下 50 MB。
Release 附件不占仓库体积，下载体验一样。

安装方式与运行时依赖见仓库根目录的 `README.md`「下载与安装」。

自行构建：

```bash
./gradlew build -x test
# 产物：build/libs/TACZ-Legacy-<version>.jar
```
