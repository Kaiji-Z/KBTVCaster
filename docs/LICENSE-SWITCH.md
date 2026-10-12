# License 切换审计：MIT → GPLv3

**切换日期：** 2026-10-13　**执行：** 目标 Round 1（Stage 0）
**决策来源：** `HANDOFF.md` §1（已拍板：转 GPLv3，站上 UxPlay 生态肩膀 + 防套壳加广告）

## 1. 贡献者审计

```
$ git log --format='%an <%ae>' | sort | uniq -c | sort -rn
     42 Sisyphus <sisyphus@opencode.local>
```

- 全部 42 个 commit 均为本机单一作者身份（历史重写后的统一身份，对应 GitHub Kaiji-Z）。
- **无第三方贡献者 → 无 relicensing 障碍。**

## 2. GPLv3 正文来源与校验

- 下载源：`https://www.gnu.org/licenses/gpl-3.0.txt`（curl 直取）
- 校验：674 行；首部 `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`；尾部 `why-not-lgpl.html` 段落结尾。与官方文本一致。

## 3. 内嵌第三方源码审计

| 组件 | 位置 | 许可证 | 证据 | 与 GPLv3 关系 |
|---|---|---|---|---|
| Cling UPnP | `app/src/main/java/org/fourthline/cling/`（533 文件） | **LGPL-2.1+ / CDDL 双许可** | 源文件头："subject to the terms of either the GNU Lesser General Public License Version 2 or later ("LGPL") or the CDDL"（如 `cling/model/types/UDN.java` 首部） | **选择 LGPL 腿**（LGPL-2.1+ 可按 LGPL §3 升级适用 GPLv3+）→ **兼容**。保持文件头与原始许可声明不动 |
| ZxtMediaRenderer DMR | `app/src/main/java/com/zxt/dlna/`（10 文件）+ `com/kbtv/caster/dlna/DLNAUtils.java` | **源自 TVRemoteIME，GPL-2.0**（源文件**无许可证头**） | 上游原仓库 `kingthy/TVRemoteIME` 已 404；现存 fork（newPersonKing/zhuziwuming/lambdalang 的 TVRemoteIME）GitHub 检测一致报 **GPL-2.0**；fork 的 LICENSE 为裸 GPLv2 文本，无 "or later" 声明 | **风险项**。GPLv2-only 与 GPLv3 单向不兼容；按 GPLv2 §9「程序未指明版本号时可任选已发布版本」存在升级解释空间，但**保守口径按 GPLv2-only 处理**。缓解：本目标期内**无任何对外分发动作**（目标停止条件明确禁发布/发版/推远端），并在 Stage A/B 对该层做 **clean-room 重写**（按 UPnP AVTransport/RenderingControl 公开规范重写，行为由 androidTest 端到端保护），重写完成后风险消除 |
| Jetty 8.1.9 | `app/libs/jetty-*.jar` | Apache-2.0 / EPL-1.0 双许可 | Jetty 8.1 官方 | 取 Apache-2.0 腿 → 单向兼容 GPLv3 |
| Apache HttpComponents 4.2.x | `httpclient/httpcore-4.2.x.jar` | Apache-2.0 | ASF 官方 | 单向兼容 |
| Cling support / seamless | `cling-support-2.0.1.jar`、`seamless-*.jar` | LGPL/CDDL 双（同 4thline 惯例） | 4thline 发布物 | 选择 LGPL 腿 → 兼容 |
| universal-image-loader 1.8.4 | `universal-image-loader-1.8.4-with-sources.jar` | Apache-2.0 | 上游 | 单向兼容 |
| javax.servlet 3.0 | `javax.servlet-3.0.0.v201103241009.jar` | EPL-1.0 / Apache-2.0（Eclipse 发布） | 文件名特征 | 取 Apache-2.0 腿 → 兼容 |
| cdi-api / javax.inject | `cdi-api.jar`、`javax.inject.jar` | Apache-2.0 | JCP/ASF 发布物 | 单向兼容 |
| javax.annotation 1.0 | `javax.annotation_1.0.jar` | **CDDL-1.0**（GlassFish） | JSR-250 参考实现 | **理论灰区**（FSF 认为 CDDL 与 GPLv3 组合分发存疑）。已列入 Stage A 检查项：grep 实际使用情况，未用则建议剔除（依赖变更需用户确认）；如被使用则记录并评估替换 |

## 4. 结论与门禁

1. **自有代码（`com.kbtv.caster.*`）即刻起以 GPLv3 授权**：LICENSE 已替换为 GPLv3 全文，README 徽章与许可节已更新。
2. **vendor 组件保留各自原始许可证**（LGPL 腿选择已记录），符合 GPLv3 §7 附加条款惯例；各源文件头未做任何改动。
3. **分发门禁：在 `com/zxt/dlna/**` + `DLNAUtils.java` clean-room 重写完成并通过端到端回归之前，不得发布任何版本**（含 GitHub Release / 应用分发）。本目标已由停止条件强制。
4. javax.annotation(CDDL) 检查项结果待 Stage A 补记于此（§3 表末行状态将更新）。

## 5. 变更清单（本次）

- `LICENSE`：MIT → GPLv3 全文（674 行）
- `README.md`：License 徽章 MIT→GPLv3；「📄 许可证」节重写并指向本文件
- `AGENTS.md`：NOTES 增加 License 条目
