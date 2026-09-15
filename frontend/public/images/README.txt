# 商品图片放置目录

将 AI 生成的商品图片按以下文件名放入本目录，Vite 构建时会自动复制到 dist/images/。
Nginx 会以 /images/xxx.png 路径对外提供访问。

## 文件命名（与 sql/init/04-pms-data.sql 中的路径一一对应）

### SPU 主图（商品列表页展示）
- spu-1-x5-main.png      → 星河 X5 Pro 智能手机 主图
- spu-2-air14-main.png   → 极光 Air14 轻薄笔记本 主图
- spu-3-t1-main.png      → 雷霆 T1 真无线蓝牙耳机 主图

### SKU 图（秒杀商品卡片展示，取 pms_sku.image）
- sku-1-x5-black.png     → 星河 X5 Pro 曜石黑 256G
- sku-2-x5-blue.png      → 星河 X5 Pro 星空蓝 512G
- sku-3-air14-16g.png    → 极光 Air14 16G+512G
- sku-4-air14-32g.png    → 极光 Air14 32G+1T
- sku-5-t1-white.png     → 雷霆 T1 珍珠白

## 图片规格建议
- 尺寸：800x800 或 1024x1024 正方形（商品卡片 object-fit: cover 裁剪）
- 格式：PNG 或 JPG（质量 85%+），文件名需与 SQL 中扩展名一致
- 背景：纯白或浅色渐变，突出产品主体
- 单张大小：< 300KB
