# 商品图片目录

本目录已包含 8 张演示商品图（3 张 SPU 主图 + 5 张 SKU 图），Vite 构建时自动复制到 dist/images/，
Nginx 以 /images/xxx.png 路径对外提供访问。文件名与 sql/init/04-pms-data.sql 中的路径一一对应，
替换图片时保持文件名不变即可。

## 文件命名

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
- 单张大小：建议 < 500KB（仓库内演示图约 0.7~1.6MB，仅用于本地演示）
