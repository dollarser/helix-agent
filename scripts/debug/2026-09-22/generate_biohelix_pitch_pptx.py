#!/usr/bin/env python3
"""
Generate BioHelix Business Plan Roadshow Presentation (PPTX)
Tailored for Changjiang 3D Scientific Computing Center & Xiaogan Synthetic Bio/Pharma Competition
"""

import sys
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

# Initialize Presentation (16:9 Widescreen)
prs = Presentation()
prs.slide_width = Inches(13.333)
prs.slide_height = Inches(7.5)

# Color Palette (Life Science & High-Tech Theme)
BG_COLOR = RGBColor(11, 19, 38)        # #0B1326 Deep Biotech Navy
CARD_BG = RGBColor(19, 31, 58)         # #131F3A Slate Biotech Blue
CARD_BORDER = RGBColor(38, 56, 94)     # #26385E Border Navy
ACCENT_GREEN = RGBColor(52, 211, 153)  # #34D399 Life Science Emerald
ACCENT_BLUE = RGBColor(56, 189, 248)   # #38BDF8 Cyan High-Tech
ACCENT_AMBER = RGBColor(251, 191, 36)  # #FBBF24 Warning / Highlight Gold
TEXT_WHITE = RGBColor(248, 250, 252)   # #F8FAFC Pure White
TEXT_MUTED = RGBColor(148, 163, 184)   # #94A3B8 Subtitle Gray
TEXT_BODY = RGBColor(203, 213, 225)    # #CBD5E1 Body Gray

def set_slide_background(slide):
    background = slide.background
    fill = background.fill
    fill.solid()
    fill.fore_color.rgb = BG_COLOR

def add_header(slide, title_text, category_text):
    tag_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.5), Inches(11.7), Inches(0.4))
    tf_tag = tag_box.text_frame
    tf_tag.word_wrap = True
    tf_tag.margin_left = tf_tag.margin_top = tf_tag.margin_right = tf_tag.margin_bottom = 0
    p_tag = tf_tag.paragraphs[0]
    p_tag.text = category_text.upper()
    p_tag.font.size = Pt(11)
    p_tag.font.bold = True
    p_tag.font.color.rgb = ACCENT_GREEN
    
    title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.85), Inches(11.7), Inches(0.7))
    tf_title = title_box.text_frame
    tf_title.word_wrap = True
    tf_title.margin_left = tf_title.margin_top = tf_title.margin_right = tf_title.margin_bottom = 0
    p_title = tf_title.paragraphs[0]
    p_title.text = title_text
    p_title.font.size = Pt(22)
    p_title.font.bold = True
    p_title.font.color.rgb = TEXT_WHITE

def add_footer(slide, current_page, total_pages=11):
    footer_box = slide.shapes.add_textbox(Inches(0.8), Inches(6.9), Inches(11.7), Inches(0.4))
    tf = footer_box.text_frame
    tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
    p = tf.paragraphs[0]
    p.text = f"BioHelix · 依托长江3D科学计算中心生命科技专项路演  |  Confidential  |  {current_page} / {total_pages}"
    p.font.size = Pt(10)
    p.font.color.rgb = TEXT_MUTED

def create_card(slide, left, top, width, height, bg_color=CARD_BG, border_color=CARD_BORDER):
    shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, left, top, width, height)
    shape.fill.solid()
    shape.fill.fore_color.rgb = bg_color
    if border_color:
        shape.line.color.rgb = border_color
        shape.line.width = Pt(1)
    else:
        shape.line.fill.background()
    return shape

blank_layout = prs.slide_layouts[6]

# ==========================================
# SLIDE 1: 封面页 (Cover)
# ==========================================
slide1 = prs.slides.add_slide(blank_layout)
set_slide_background(slide1)

create_card(slide1, Inches(0.8), Inches(1.1), Inches(11.733), Inches(5.3), bg_color=RGBColor(16, 26, 50), border_color=RGBColor(35, 55, 95))

# Badge
badge = create_card(slide1, Inches(1.4), Inches(1.6), Inches(4.2), Inches(0.45), bg_color=RGBColor(20, 50, 40), border_color=ACCENT_GREEN)
tf_badge = badge.text_frame
tf_badge.vertical_anchor = MSO_ANCHOR.MIDDLE
p_b = tf_badge.paragraphs[0]
p_b.text = "长江3D科学计算中心 · 生命科学与算力基座专项"
p_b.font.size = Pt(11)
p_b.font.bold = True
p_b.font.color.rgb = ACCENT_GREEN
p_b.alignment = PP_ALIGN.CENTER

# Main Title
title_box1 = slide1.shapes.add_textbox(Inches(1.4), Inches(2.3), Inches(10.5), Inches(2.2))
tf1 = title_box1.text_frame
tf1.word_wrap = True
p1 = tf1.paragraphs[0]
p1.text = "BioHelix 移动端生命科学 AI 执行工作台"
p1.font.size = Pt(34)
p1.font.bold = True
p1.font.color.rgb = TEXT_WHITE

p2 = tf1.add_paragraph()
p2.text = "构建长江3D科学计算中心的掌上智能调度中枢 —— 从微观实验解析到宏观超算支撑"
p2.font.size = Pt(17)
p2.font.color.rgb = ACCENT_BLUE
p2.space_before = Pt(12)

p3 = tf1.add_paragraph()
p3.text = "参赛领域：AI 前沿交叉生命科技与算力基础开发应用  |  赋能：创新药物筛选 · 孝感合成生物中试"
p3.font.size = Pt(13)
p3.font.color.rgb = TEXT_MUTED
p3.space_before = Pt(14)

# Meta info
meta_box = slide1.shapes.add_textbox(Inches(1.4), Inches(5.3), Inches(10.5), Inches(0.6))
tf_meta = meta_box.text_frame
p_meta = tf_meta.paragraphs[0]
p_meta.text = "申报项目：BioHelix 端-超协同智能中枢   |   团队：BioHelix 创新联合体   |   2026 年 9 月"
p_meta.font.size = Pt(12)
p_meta.font.color.rgb = TEXT_BODY

# ==========================================
# SLIDE 2: 项目概述 (Executive Summary)
# ==========================================
slide2 = prs.slides.add_slide(blank_layout)
set_slide_background(slide2)
add_header(slide2, "项目概述：打造生物医药与生命科技的移动算力新基建", "Executive Summary")
add_footer(slide2, 2)

# Top 300-word overview card
ov_card = create_card(slide2, Inches(0.8), Inches(1.65), Inches(11.733), Inches(2.3), bg_color=CARD_BG)
tf_ov = ov_card.text_frame
tf_ov.word_wrap = True
tf_ov.margin_left = Inches(0.3)
tf_ov.margin_right = Inches(0.3)
tf_ov.margin_top = Inches(0.22)

p_ov_title = tf_ov.paragraphs[0]
p_ov_title.text = "【项目核心概述 · 280字业内标准表达】"
p_ov_title.font.size = Pt(12.5)
p_ov_title.font.bold = True
p_ov_title.font.color.rgb = ACCENT_AMBER

p_ov_body = tf_ov.add_paragraph()
p_ov_body.text = (
    "BioHelix 是一款面向生命科技与生物医药的端-超协同移动 AI 执行工作台。项目针对实验台、洁净室及野外采样等现场“无电脑可用、涉密数据难出网、离线计算受限”的痛点，依托 Android 本机多执行域沙箱（QuickJS 与 PRoot Linux 容器），在移动端实现分子数据（PDB/FASTA/SDF）就地清洗、微量生信计算与实验流程自动化。\n\n"
    "平台深度对接长江3D科学计算中心，构筑“端侧微观数据预处理 → 超算宏观高通量仿真（虚拟筛选、分子动力学与酶催化推演） → 移动端流式对账与实验指导”的完整闭环。产品直接赋能创新药物研发、中药野外科考与孝感合成生物基地中试现场巡检，构建生命科学计算基础设施的掌上新质生产力底座。"
)
p_ov_body.font.size = Pt(11.5)
p_ov_body.font.color.rgb = TEXT_BODY
p_ov_body.space_before = Pt(6)

# Bottom 3 key pillars
metrics = [
    ("微观端侧：现场闭环", "洁净室/野外科考现场无需电脑，本地沙箱完成分子结构与基因序列格式清洗与轻量计算", ACCENT_GREEN),
    ("宏观算力：端-超协同", "深度对接长江3D科学计算中心，在手机端自然语言一键下发千万级大分子对接与动力学仿真", ACCENT_BLUE),
    ("产业落地：孝感示范", "无缝对接孝感合成生物基地发酵中试与医药产业，实现生物反应器巡检与工艺就地调控", ACCENT_AMBER),
]

card_w = Inches(3.75)
card_h = Inches(2.4)
card_gap = Inches(0.24)
start_x = Inches(0.8)

for i, (m_title, m_desc, m_color) in enumerate(metrics):
    x = start_x + i * (card_w + card_gap)
    m_card = create_card(slide2, x, Inches(4.2), card_w, card_h)
    tf_m = m_card.text_frame
    tf_m.word_wrap = True
    tf_m.margin_left = Inches(0.25)
    tf_m.margin_right = Inches(0.25)
    tf_m.margin_top = Inches(0.25)
    
    p_mt = tf_m.paragraphs[0]
    p_mt.text = m_title
    p_mt.font.size = Pt(15)
    p_mt.font.bold = True
    p_mt.font.color.rgb = m_color
    
    p_md = tf_m.add_paragraph()
    p_md.text = m_desc
    p_md.font.size = Pt(11.5)
    p_md.font.color.rgb = TEXT_BODY
    p_md.space_before = Pt(10)

# ==========================================
# SLIDE 3: 行业痛点与大赛契合
# ==========================================
slide3 = prs.slides.add_slide(blank_layout)
set_slide_background(slide3)
add_header(slide3, "行业痛点：生物医药研发“重计算在超算，真实验在现场”的断层", "Market Problem")
add_footer(slide3, 3)

pain_points = [
    ("痛点一：实验现场缺乏计算工具", "场景断层", "P2/P3 洁净室、生物反应器车间、野外采样区严禁或无法携带笨重电脑，科研人员在实验台前无法即时调用生信计算与对比文献。"),
    ("痛点二：超算算力调度割裂与滞后", "调度断层", "长江3D科学计算中心算力极其强大，但科研人员离开工位就无法掌握任务队列、监控收敛日志，出现排队报错无法第一时间响应。"),
    ("痛点三：涉密数据与弱网外业困境", "安全断层", "核心候选药物与菌株序列涉及重大利益，严禁上传公网商业模型；野外中药科考弱网无网环境下，依赖云端运算的系统彻底瘫痪。"),
]

for i, (p_title, p_tag, p_desc) in enumerate(pain_points):
    x = start_x + i * (card_w + card_gap)
    p_card = create_card(slide3, x, Inches(1.65), card_w, Inches(3.6))
    tf_p = p_card.text_frame
    tf_p.word_wrap = True
    tf_p.margin_left = Inches(0.25)
    tf_p.margin_right = Inches(0.25)
    tf_p.margin_top = Inches(0.3)
    
    p_tag_p = tf_p.paragraphs[0]
    p_tag_p.text = p_tag.upper()
    p_tag_p.font.size = Pt(11)
    p_tag_p.font.bold = True
    p_tag_p.font.color.rgb = ACCENT_AMBER
    
    p_tit_p = tf_p.add_paragraph()
    p_tit_p.text = p_title
    p_tit_p.font.size = Pt(15)
    p_tit_p.font.bold = True
    p_tit_p.font.color.rgb = TEXT_WHITE
    p_tit_p.space_before = Pt(8)
    
    p_desc_p = tf_p.add_paragraph()
    p_desc_p.text = p_desc
    p_desc_p.font.size = Pt(12)
    p_desc_p.font.color.rgb = TEXT_BODY
    p_desc_p.space_before = Pt(12)

opp_card = create_card(slide3, Inches(0.8), Inches(5.45), Inches(11.733), Inches(1.15), bg_color=RGBColor(20, 40, 50), border_color=ACCENT_GREEN)
tf_opp = opp_card.text_frame
tf_opp.word_wrap = True
tf_opp.margin_left = Inches(0.3)
tf_opp.margin_top = Inches(0.2)
p_opp = tf_opp.paragraphs[0]
p_opp.text = "破局解法：BioHelix 深度契合大赛赛道（三）—— 构建端-超协同的生命科学计算基础设施"
p_opp.font.size = Pt(13.5)
p_opp.font.bold = True
p_opp.font.color.rgb = ACCENT_GREEN

p_opp_sub = tf_opp.add_paragraph()
p_opp_sub.text = "打通从“移动端微观实验数据清洗”到“长江3D科学计算中心宏观算力仿真”的最后一公里，将超级算力直达实验第一线！"
p_opp_sub.font.size = Pt(11.5)
p_opp_sub.font.color.rgb = TEXT_BODY
p_opp_sub.space_before = Pt(4)

# ==========================================
# SLIDE 4: 核心产品与解决方案
# ==========================================
slide4 = prs.slides.add_slide(blank_layout)
set_slide_background(slide4)
add_header(slide4, "产品方案：BioHelix —— 长江3D科学计算中心的掌上智能调度中枢", "Solution")
add_footer(slide4, 4)

pillars = [
    ("01 移动端微观数据本地闭环", "支持 SAF 与本地存储授权，内置 Biopython/RDKit，可在手机端就地解析 PDB、FASTA、SDF 结构，进行格式校验、拓扑属性计算与实验日志原子化记录。"),
    ("02 长江3D科算中心一键调度", "自研 Tool Dispatcher 内置 HPC 适配器。科学家用自然语言即可一键下发分子对接、分子动力学模拟或酶催化推演，自动编译作业脚本提交集群。"),
    ("03 实时流式终端与长任务对账", "长任务计算过程通过 PTY 终端流式回传手机，实时查看能量最小化、RMSD 收敛曲线；断网或杀进程后支持 Room 状态自动对账，绝不盲目重放。"),
    ("04 涉密新药数据 100% 物理隔离", "轻量规则进 QuickJS isolated 沙箱，重度编译进 PRoot 容器，核心实验资产不出端；支持直连局域网私有大模型与专属计算集群。"),
]

for i, (pil_title, pil_desc) in enumerate(pillars):
    col = i % 2
    row = i // 2
    x = Inches(0.8) + col * Inches(5.95)
    y = Inches(1.65) + row * Inches(2.45)
    
    pil_card = create_card(slide4, x, y, Inches(5.75), Inches(2.25))
    tf_pil = pil_card.text_frame
    tf_pil.word_wrap = True
    tf_pil.margin_left = Inches(0.3)
    tf_pil.margin_right = Inches(0.3)
    tf_pil.margin_top = Inches(0.25)
    
    p_pt = tf_pil.paragraphs[0]
    p_pt.text = pil_title
    p_pt.font.size = Pt(16)
    p_pt.font.bold = True
    p_pt.font.color.rgb = ACCENT_GREEN
    
    p_pd = tf_pil.add_paragraph()
    p_pd.text = pil_desc
    p_pd.font.size = Pt(12)
    p_pd.font.color.rgb = TEXT_BODY
    p_pd.space_before = Pt(10)

# ==========================================
# SLIDE 5: 深度技术架构
# ==========================================
slide5 = prs.slides.add_slide(blank_layout)
set_slide_background(slide5)
add_header(slide5, "技术架构：端-超协同、物理隔离沙箱与高可靠生信调度", "Technology Architecture")
add_footer(slide5, 5)

# Left Column
left_card = create_card(slide5, Inches(0.8), Inches(1.65), Inches(5.75), Inches(4.9))
tf_left = left_card.text_frame
tf_left.word_wrap = True
tf_left.margin_left = Inches(0.3)
tf_left.margin_right = Inches(0.3)
tf_left.margin_top = Inches(0.3)

p_lt = tf_left.paragraphs[0]
p_lt.text = "端-超协同系统架构分层"
p_lt.font.size = Pt(16)
p_lt.font.bold = True
p_lt.font.color.rgb = ACCENT_GREEN

arch_layers = [
    ("移动交互层 (Touch UI)", "面向生信实验优化，支持 3D 分子轻量预览、Diff 比对、实时 Pty 输出"),
    ("协同中枢 (Coordinator)", "任务意图解析、多模态附件还原、生信上下文组装与作业生命周期管理"),
    ("调度管线 (Dispatcher)", "Schema 校验 -> 生信权限门禁 -> 策略评估 -> 超算 HPC 适配下发"),
    ("端侧隔离域 (Edge Runtimes)", "QuickJS 独立 UID 沙箱 (微规则) + PRoot 容器化 Linux (Biopython/RDKit)"),
    ("超算集群 (Changjiang 3D HPC)", "对接长江3D科学计算中心，承载万核级分子对接、动力学模拟与大模型"),
]

for l_name, l_desc in arch_layers:
    p_l1 = tf_left.add_paragraph()
    p_l1.text = f"●  {l_name}"
    p_l1.font.size = Pt(12)
    p_l1.font.bold = True
    p_l1.font.color.rgb = TEXT_WHITE
    p_l1.space_before = Pt(8)
    
    p_l2 = tf_left.add_paragraph()
    p_l2.text = f"    {l_desc}"
    p_l2.font.size = Pt(10.5)
    p_l2.font.color.rgb = TEXT_MUTED

# Right Column
right_card = create_card(slide5, Inches(6.8), Inches(1.65), Inches(5.733), Inches(4.9))
tf_right = right_card.text_frame
tf_right.word_wrap = True
tf_right.margin_left = Inches(0.3)
tf_right.margin_right = Inches(0.3)
tf_right.margin_top = Inches(0.3)

p_rt = tf_right.paragraphs[0]
p_rt.text = "生信场景核心技术壁垒"
p_rt.font.size = Pt(16)
p_rt.font.bold = True
p_rt.font.color.rgb = ACCENT_BLUE

moats = [
    ("免 Root 容器化生信环境移植", "攻克 Android 系统底层限制，在普通商用手机上免 Root 稳定运行 Linux 生信运行时与 C++ 化学信息学动态库。"),
    ("长江3D科算中心流式调度协议", "创新设计端-超轻量安全代理协议，毫秒级下发 Slurm/PBS 作业脚本，并通过 PFD/Binder 安全通道回传监控流。"),
    ("涉密级防泄漏与确定性审计", "杜绝任何模型幻觉引发的数据误篡改，所有文件变动支持原子级 Trash 恢复与 Room 数据库全周期合规审计。"),
]

for m_name, m_desc in moats:
    p_m1 = tf_right.add_paragraph()
    p_m1.text = f"★  {m_name}"
    p_m1.font.size = Pt(13)
    p_m1.font.bold = True
    p_m1.font.color.rgb = ACCENT_BLUE
    p_m1.space_before = Pt(14)
    
    p_m2 = tf_right.add_paragraph()
    p_m2.text = m_desc
    p_m2.font.size = Pt(11)
    p_m2.font.color.rgb = TEXT_BODY
    p_m2.space_before = Pt(4)

# ==========================================
# SLIDE 6: 科算应用场景 (契合四大赛道)
# ==========================================
slide6 = prs.slides.add_slide(blank_layout)
set_slide_background(slide6)
add_header(slide6, "科算应用：全方位赋能新药研发、合成生物与中药产业", "Scientific Applications")
add_footer(slide6, 6)

sci_apps = [
    ("场景一：创新药物与中药野外科考", "领域一：新药/中药", "野外道地药材与植物采样无网环境下，手机离线识别物种并进行图谱初筛；联网后一键向长江3D科算中心提交靶点对接，反向推演活性成分。"),
    ("场景二：孝感合成生物基地中试巡检", "领域二：生物制造", "在孝感中试车间，巡检人员手持终端读取发酵罐传感器数据，本地评估溶氧与pH；调用3D科算中心酶动力学模型秒级推演补料调控方案。"),
    ("场景三：端-超协同生命计算基础设施", "领域三：主申报赛道", "为高校与药企打造新一代生信工作台，实验科学家在洁净室内随手唤醒超算集群，完成微观参数到宏观万核仿真的秒级闭环。"),
    ("场景四：智能医疗器械与精准医疗", "领域四：智能器械", "对接便携床旁检验设备（POCT），移动端就地完成原始电生理/光谱信号降噪，联动3D科算中心大模型提供多模态精准辅助诊断。"),
]

for i, (s_title, s_tag, s_desc) in enumerate(sci_apps):
    col = i % 2
    row = i // 2
    x = Inches(0.8) + col * Inches(5.95)
    y = Inches(1.65) + row * Inches(2.45)
    
    s_card = create_card(slide6, x, y, Inches(5.75), Inches(2.25))
    tf_s = s_card.text_frame
    tf_s.word_wrap = True
    tf_s.margin_left = Inches(0.3)
    tf_s.margin_right = Inches(0.3)
    tf_s.margin_top = Inches(0.2)
    
    p_st = tf_s.paragraphs[0]
    p_st.text = s_title
    p_st.font.size = Pt(15)
    p_st.font.bold = True
    p_st.font.color.rgb = ACCENT_AMBER
    
    p_stag = tf_s.add_paragraph()
    p_stag.text = f"对接赛道：{s_tag}"
    p_stag.font.size = Pt(11)
    p_stag.font.color.rgb = ACCENT_GREEN
    p_stag.space_before = Pt(2)
    
    p_sd = tf_s.add_paragraph()
    p_sd.text = s_desc
    p_sd.font.size = Pt(11)
    p_sd.font.color.rgb = TEXT_BODY
    p_sd.space_before = Pt(6)

# ==========================================
# SLIDE 7: 商业模式与产业落地
# ==========================================
slide7 = prs.slides.add_slide(blank_layout)
set_slide_background(slide7)
add_header(slide7, "商业模式：立足科研生态，服务药企研发与生物制造基地", "Business Model")
add_footer(slide7, 7)

biz_models = [
    ("科研/高校免费版", "构建学术生态与用户漏斗", [
        "全功能生信文件管理与格式转换",
        "标准微量计算与本地离线沙箱",
        "基础科研工作流与文献提取模板",
        "渗透全国生物医药高校与课题组",
    ], CARD_BORDER, TEXT_WHITE),
    ("Pro 生信专业版", "面向专业工程师与研究员", [
        "PRoot 完整生信 Linux 容器持续自愈",
        "长江3D科算中心高速作业调度通道",
        "实时长任务终端 (PTY) 与无损恢复",
        "支持个人买断与年度维护订阅",
    ], ACCENT_GREEN, ACCENT_GREEN),
    ("孝感基地与企业套件", "面向基地中试车间与涉密药企", [
        "孝感合成生物基地发酵巡检专属插件",
        "药企涉密局域网私有超算与大模型接入",
        "生物反应器工业协议与传感器直连",
        "提供机构级合规审计与现场运维保障",
    ], ACCENT_BLUE, ACCENT_BLUE),
]

for i, (bm_title, bm_sub, bm_items, border_c, title_c) in enumerate(biz_models):
    x = start_x + i * (card_w + card_gap)
    b_card = create_card(slide7, x, Inches(1.65), card_w, Inches(4.9), border_color=border_c)
    tf_b = b_card.text_frame
    tf_b.word_wrap = True
    tf_b.margin_left = Inches(0.25)
    tf_b.margin_right = Inches(0.25)
    tf_b.margin_top = Inches(0.3)
    
    p_bt = tf_b.paragraphs[0]
    p_bt.text = bm_title
    p_bt.font.size = Pt(16.5)
    p_bt.font.bold = True
    p_bt.font.color.rgb = title_c
    
    p_bs = tf_b.add_paragraph()
    p_bs.text = bm_sub
    p_bs.font.size = Pt(11)
    p_bs.font.color.rgb = TEXT_MUTED
    p_bs.space_before = Pt(4)
    
    for item in bm_items:
        p_bi = tf_b.add_paragraph()
        p_bi.text = f"✔  {item}"
        p_bi.font.size = Pt(11.5)
        p_bi.font.color.rgb = TEXT_BODY
        p_bi.space_before = Pt(12)

# ==========================================
# SLIDE 8: 落地规划与长江3D科算中心协同
# ==========================================
slide8 = prs.slides.add_slide(blank_layout)
set_slide_background(slide8)
add_header(slide8, "落地规划：与长江3D科算中心及孝感基地共筑产业标杆", "Roadmap & Milestones")
add_footer(slide8, 8)

milestones = [
    ("Phase 1: 端侧沙箱就绪", "已就绪验证", "● 完成 Android 多执行域与安全沙箱\n● 成功跑通移动端 Biopython/RDKit\n● 交付 SAF 生信文件读写与原子写入\n● 验证离线单机数据清洗闭环"),
    ("Phase 2: 3D科算深度对接", "大赛签约期 (1-3月)", "● 深度对接长江3D科学计算中心 API\n● 跑通移动端一键下发分子对接与仿真\n● 交付流式 PTY 终端与收敛日志推流\n● 联合举办生信移动工作台内测"),
    ("Phase 3: 孝感基地中试试点", "产业试点期 (4-8月)", "● 进驻孝感合成生物制造基地开展试点\n● 部署生物反应器中试掌上巡检方案\n● 联合中医药机构实测野外离线采样\n● 推出首个生命科技商业化订阅包"),
    ("Phase 4: 全行业规模化繁荣", "规模推广期 (9-18月)", "● 接入 100+ 款主流生信/制药工具链\n● 打造生命科学移动算力调度事实标准\n● 覆盖全国 100+ 重点医药高校与药企\n● 实现端-超协同生态规模化盈利"),
]

card_w4 = Inches(2.78)
card_gap4 = Inches(0.2)
start_x4 = Inches(0.8)

for i, (m_title, m_time, m_content) in enumerate(milestones):
    x = start_x4 + i * (card_w4 + card_gap4)
    m_card = create_card(slide8, x, Inches(1.65), card_w4, Inches(4.9))
    tf_m = m_card.text_frame
    tf_m.word_wrap = True
    tf_m.margin_left = Inches(0.2)
    tf_m.margin_right = Inches(0.2)
    tf_m.margin_top = Inches(0.3)
    
    p_mt = tf_m.paragraphs[0]
    p_mt.text = m_title
    p_mt.font.size = Pt(14)
    p_mt.font.bold = True
    p_mt.font.color.rgb = ACCENT_GREEN if i < 2 else ACCENT_BLUE
    
    p_mtime = tf_m.add_paragraph()
    p_mtime.text = m_time
    p_mtime.font.size = Pt(11)
    p_mtime.font.color.rgb = ACCENT_AMBER
    p_mtime.space_before = Pt(4)
    
    p_mc = tf_m.add_paragraph()
    p_mc.text = m_content
    p_mc.font.size = Pt(10.5)
    p_mc.font.color.rgb = TEXT_BODY
    p_mc.space_before = Pt(14)

# ==========================================
# SLIDE 9: 核心团队与技术壁垒
# ==========================================
slide9 = prs.slides.add_slide(blank_layout)
set_slide_background(slide9)
add_header(slide9, "核心团队与竞争优势：跨界融合的底层工程与计算生物基因", "Team & Competency")
add_footer(slide9, 9)

team_members = [
    ("移动系统与容器底层专家", "联合创始人 / 系统架构师", "前头部科技企业系统架构专家，深耕 Linux 容器化、Android 内核及多进程 IPC 通信，主导过大规模移动沙箱与底层虚拟化工程项目。"),
    ("计算生物学与 AI 制药专家", "联合创始人 / AI 科学家", "知名药企与生物计算实验室背景，精通分子对接、高通量分子动力学模拟算法与超算集群调度，发表多篇生信高水平论文。"),
    ("生物制造与外业场景专家", "产品与产业化负责人", "具备大型生物化工中试发酵车间实战管理与重大科研外业科考实施经验，深刻理解实验现场与工业车间的严苛需求。"),
]

for i, (t_role, t_title, t_desc) in enumerate(team_members):
    x = start_x + i * (card_w + card_gap)
    t_card = create_card(slide9, x, Inches(1.65), card_w, Inches(2.7))
    tf_t = t_card.text_frame
    tf_t.word_wrap = True
    tf_t.margin_left = Inches(0.25)
    tf_t.margin_right = Inches(0.25)
    tf_t.margin_top = Inches(0.25)
    
    p_tt = tf_t.paragraphs[0]
    p_tt.text = t_role
    p_tt.font.size = Pt(15)
    p_tt.font.bold = True
    p_tt.font.color.rgb = TEXT_WHITE
    
    p_ts = tf_t.add_paragraph()
    p_ts.text = t_title
    p_ts.font.size = Pt(11)
    p_ts.font.color.rgb = ACCENT_GREEN
    p_ts.space_before = Pt(4)
    
    p_td = tf_t.add_paragraph()
    p_td.text = t_desc
    p_td.font.size = Pt(11)
    p_td.font.color.rgb = TEXT_BODY
    p_td.space_before = Pt(8)

moat_bottom = create_card(slide9, Inches(0.8), Inches(4.6), Inches(11.733), Inches(2.0), bg_color=RGBColor(16, 36, 46), border_color=ACCENT_GREEN)
tf_mb = moat_bottom.text_frame
tf_mb.word_wrap = True
tf_mb.margin_left = Inches(0.3)
tf_mb.margin_top = Inches(0.2)

p_mbt = tf_mb.paragraphs[0]
p_mbt.text = "差异化竞争壁垒：端-超协同稀缺性 + 在地化产业强绑定"
p_mbt.font.size = Pt(15)
p_mbt.font.bold = True
p_mbt.font.color.rgb = ACCENT_GREEN

p_mbb = tf_mb.add_paragraph()
p_mbb.text = (
    "1. 全球稀缺的端-超协同移动基座：打破传统超算必须绑死电脑桌面的宿命，让超级算力真正渗透进湿实验台与发酵车间；\n"
    "2. 极高工程门槛的免 Root 生信沙箱：在 Android 体系下安全调谐容器化生信环境，兼具高安全合规与专业生信计算能力；\n"
    "3. 长江3D科算中心与孝感基地的深度赋能：依托区域顶尖算力与产业化基地，构建不可复制的产学研用落地闭环。"
)
p_mbb.font.size = Pt(11.5)
p_mbb.font.color.rgb = TEXT_BODY
p_mbb.space_before = Pt(6)

# ==========================================
# SLIDE 10: 融资与产业协同诉求
# ==========================================
slide10 = prs.slides.add_slide(blank_layout)
set_slide_background(slide10)
add_header(slide10, "融资需求与大赛协同：借力科算中心算力，加速孝感中试落地", "Financing & Collaboration")
add_footer(slide10, 10)

card_fin_l = create_card(slide10, Inches(0.8), Inches(1.65), Inches(5.75), Inches(4.9))
tf_fl = card_fin_l.text_frame
tf_fl.word_wrap = True
tf_fl.margin_left = Inches(0.3)
tf_fl.margin_right = Inches(0.3)
tf_fl.margin_top = Inches(0.3)

p_flt = tf_fl.paragraphs[0]
p_flt.text = "融资诉求与大赛支持"
p_flt.font.size = Pt(16)
p_flt.font.bold = True
p_flt.font.color.rgb = ACCENT_GREEN

fin_items = [
    ("融资轮次与诉求", "寻求天使轮 / 种子轮股权融资，出让 10% - 15% 股权"),
    ("长江3D科算中心算力支持", "申请接入 3D 科算中心开放节点，联合开发分子动力学与生信流式调度专属通道"),
    ("孝感生物制造场景入驻", "申请进驻孝感合成生物制造基地，实地部署发酵罐智能巡检试点"),
    ("55% 资金投向技术研发", "端-超协同调度优化、移动端 3D 分子低功耗流式渲染与轻量量化模型"),
]

for fi_title, fi_desc in fin_items:
    p_f1 = tf_fl.add_paragraph()
    p_f1.text = f"◆  {fi_title}"
    p_f1.font.size = Pt(12.5)
    p_f1.font.bold = True
    p_f1.font.color.rgb = TEXT_WHITE
    p_f1.space_before = Pt(10)
    
    p_f2 = tf_fl.add_paragraph()
    p_f2.text = f"    {fi_desc}"
    p_f2.font.size = Pt(11)
    p_f2.font.color.rgb = TEXT_BODY

card_fin_r = create_card(slide10, Inches(6.8), Inches(1.65), Inches(5.733), Inches(4.9))
tf_fr = card_fin_r.text_frame
tf_fr.word_wrap = True
tf_fr.margin_left = Inches(0.3)
tf_fr.margin_right = Inches(0.3)
tf_fr.margin_top = Inches(0.3)

p_frt = tf_fr.paragraphs[0]
p_frt.text = "未来 12 个月产业落地目标"
p_frt.font.size = Pt(16)
p_frt.font.bold = True
p_frt.font.color.rgb = ACCENT_BLUE

milestone_targets = [
    ("长江3D算力消耗", "实现年度调度长江3D科算中心 500,000+ 核时，成为科算中心标杆应用案例"),
    ("孝感中试示范", "在孝感合成生物基地落地 2 家以上示范车间，助力中试周期缩短 30% 以上"),
    ("用户与科研合作", "覆盖 30+ 所高校生物医药国家重点实验室，激活 10,000+ 名生信科研人员"),
    ("商业订阅验证", "实现月度经常性收入 (MRR) 突破 50 万元人民币，构建正向商业造血循环"),
]

for mt_title, mt_desc in milestone_targets:
    p_m1 = tf_fr.add_paragraph()
    p_m1.text = f"🎯  {mt_title}"
    p_m1.font.size = Pt(13)
    p_m1.font.bold = True
    p_m1.font.color.rgb = ACCENT_BLUE
    p_m1.space_before = Pt(12)
    
    p_m2 = tf_fr.add_paragraph()
    p_m2.text = mt_desc
    p_m2.font.size = Pt(11)
    p_m2.font.color.rgb = TEXT_BODY
    p_m2.space_before = Pt(3)

# ==========================================
# SLIDE 11: 封底
# ==========================================
slide11 = prs.slides.add_slide(blank_layout)
set_slide_background(slide11)

create_card(slide11, Inches(0.8), Inches(1.1), Inches(11.733), Inches(5.3), bg_color=RGBColor(16, 26, 50), border_color=RGBColor(35, 55, 95))

end_box = slide11.shapes.add_textbox(Inches(1.4), Inches(2.2), Inches(10.5), Inches(3.0))
tf_end = end_box.text_frame
tf_end.word_wrap = True

p_e1 = tf_end.paragraphs[0]
p_e1.text = "让超级算力直达生命科学实验第一线"
p_e1.font.size = Pt(32)
p_e1.font.bold = True
p_e1.font.color.rgb = TEXT_WHITE

p_e2 = tf_end.add_paragraph()
p_e2.text = "BioHelix —— 赋能长江3D科学计算中心，助力孝感生物制造腾飞"
p_e2.font.size = Pt(18)
p_e2.font.color.rgb = ACCENT_GREEN
p_e2.space_before = Pt(12)

p_e3 = tf_end.add_paragraph()
p_e3.text = "欢迎各位领导、评委专家指导与交流！\n\n现场系统演示 Demo · 端-超调度实测 · 答辩问答"
p_e3.font.size = Pt(14)
p_e3.font.color.rgb = TEXT_BODY
p_e3.space_before = Pt(20)

# Save presentation
output_path = "/Users/dollars/Helix-biohelix/docs/product/BioHelix_商业计划书_路演版.pptx"
prs.save(output_path)
print(f"BioHelix Roadshow Presentation saved to: {output_path}")
