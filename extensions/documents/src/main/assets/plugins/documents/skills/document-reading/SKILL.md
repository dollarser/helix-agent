---
name: document-reading
description: Read and summarize authorized PDF and DOCX documents with source locations and honest text/visual coverage
---
# Document reading

1. Use the selected Documents plugin and current documents.inspect/read/view schemas. An attachment does not select the plugin or grant file access.
2. Inspect the exact source. Use its documentRef and sourceSha256 for subsequent reads; inspect again only to refresh the version or recover REF_EXPIRED.
3. For PDF, read relevant 1-based pages. Empty text on older Android or scanned pages requires view and a vision-capable model. Use view for tables, charts and multi-column layout; normalized regions [left,top,right,bottom] allow bounded close inspection.
4. For DOCX, read body blocks in order, follow nextCursor as startBlock, and retain heading and table row/cell locations. View embedded imageId near the relevant text. DOCX has no reliable page numbers. Disclose unsupported charts/OLE/SmartArt/external images and omitted headers/notes; suggest PDF export if needed.
5. Answer with observed page/block/image locations, source hash and coverage. Inspect, previews, selected pages and truncated blocks do not establish full-document coverage. imagePrepared=false means pixels were not prepared; prepared images still follow the current request's vision/data policy.
6. Save a summary only when requested using normal write/edit tools and verify it. Do not modify the source or install helpers. If denied, seek the proper user authorization rather than bypassing it.
