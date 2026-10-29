# 2026-10-29 单据与附件的实际 PDF 页面预览

销售单、退货单及 PDF 履约附件新增统一的多页图片接口。图片来自实际 PDF 渲染，包含原 PDF 的商品型号、费用、制单/审核姓名和客户签字，不以字段卡片替代单据正文。原 PDF 查看/下载接口保持原状。

## 接口

- `GET /api/trade-documents/{id}/preview-manifest`
- `GET /api/contract-attachments/{id}/preview-manifest`
- `GET /api/trade-documents/{id}/preview-chunk-data?page=1&previewKey=...&offset=0&size=655360`
- `GET /api/contract-attachments/{id}/preview-chunk-data?page=1&previewKey=...&offset=0&size=655360`

清单的 `data` 为 `{totalPages, previewKey}`，页码从 1 开始；`previewKey` 为 64 位十六进制摘要。图片分片沿用 `FileChunkDataPayload`，类型为 `image/png`，最大 640 KiB。页码、偏移和分片长度受校验。预览版本变化返回业务码 409，前端需重新获取完整清单，避免混用新旧页面。

单据版本包含实际生成依赖的 content、状态、单据号/类型/来源和签名姓名、时间、字节；捕获这些数据后再生成同一快照。不会用 OpenPDF 自动创建时间作为版本。附件版本包含实际原文件字节摘要。每次清单及分片请求都先通过原 `getDocument` / `getFile` 校验草稿可见性、所属合同与企业、附件分类权限及存储完整性，再查渲染缓存；预览 key 不是访问授权。

## 渲染与边界

共享 `PdfPagePreview` 使用 Apache PDFBox 3.0.8。版本及修复状态参考 [Apache PDFBox 官方下载](https://pdfbox.apache.org/download.cgi) 和 [安全公告](https://pdfbox.apache.org/security.html)。

按请求渲染指定页，不一次将所有页面转成图片。每页最大约 400 万像素、最长边 2400 像素；支持最多 500 页、PDF 输入最多 32 MiB。PDF 缓存最多 32 MiB，图片缓存最多 64 MiB，10 分钟过期。加密、损坏或超出预览边界的文件保留原 PDF 打开/下载入口，不伪造正文或悄悄只显示第一页。

本次新增依赖和后端接口，需要与对应前端版本配套发布；没有数据库迁移，未部署线上。

## 验证

- 交易、结算及依赖模块完整单元测试 758 项通过，无失败或跳过。日志 `/private/tmp/sqt-pdf-preview-backend-all-tests-20261029.log`。
- 渲染、权限、版本、边界专项测试 12 项通过；包括实际多页图片、缓存复用、权限丢失后缓存不可读、签名/内容变更、错误页号/超大分片、输入/像素上限、加密与损坏文件。日志 `/private/tmp/sqt-pdf-preview-focused-tests-20261029.log`。
- API baseline 与源码 194 条映射一致；部署架构 Python 测试 4 项通过。日志 `/private/tmp/sqt-pdf-preview-api-architecture-20261029.log`。
- 最后样例测试验证并导出包含合成签字的中文销售单/退货单 PDF 和实际 PNG，含两页销售单。文件位于 `exports/pdf-page-preview-20261029/`；导出日志 `/private/tmp/sqt-pdf-preview-samples-final-20261029.log`。

## 配套小程序

销售单、退货单及 PDF 附件的内容区直接展示实际页面图片；下方保留查看、下载 PDF，业务操作统一放进右上角“更多”。销售确认、签名、选仓、作废审批仍调用原处理函数。共享 PDF 读取工具最多并行两页，支持逐页重试、版本变更清屏及完整重载、同路径下载去重，并在卸载、记录或登录/企业切换后禁止旧响应回写。

履约概览的加号缩为小号线条图标，保留点击区域；“全部 N 条”位于每组记录底部。前端完整 470 + 89 + 12 项测试通过，日志 `/private/tmp/sqt-pdf-page-preview-front-tests-20261029.log`。320、375、430px 宽度布局检查通过；实际 PNG 嵌入的布局预览位于 `exports/pdf-reader-layout-20261029/`，使用合成测试内容，仍需微信真机验收。
