# 实际 PDF 页面预览验证样例

均为合成业务数据，由生产 BusinessDocumentPdfServiceImpl 生成 PDF，再使用本次 PdfPagePreview 渲染原 PDF 页面。

- sales.pdf / return.pdf：单页销售单与退货单，包含中文企业及商品、型号、规格、运输费、制单/审核姓名和合成客户签名。
- sales-multipage.pdf：42 行，两页，验证完整页面而非只显示第一页。
- 对应 *-page-N.png 为实际 PDF 页面图片；并非结构化字段卡片或人工重绘。

这些是可视检查样例，未发布到线上。
