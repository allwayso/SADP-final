"""Fill the retained course template. Run with the Codex bundled Python (python-docx).

Generate PNG/SVG diagrams from docs/assets/architecture-report/*.puml with PlantUML
before running. Only document.xml, its relationships, new styles and image content
types/media are replaced; all other source-template package parts are preserved.
"""
from copy import deepcopy
from hashlib import sha256
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import json
from lxml import etree
from docx import Document
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / 'docs'
FIG = DOCS / 'assets/architecture-report'
TEMPLATE = DOCS / '《软件体系结构与设计模式》综合实践报告_组长学号_组长姓名.docx'
OUTPUT = DOCS / '《软件体系结构与设计模式》综合实践报告_2024302131052_徐晨帆.docx'
TEMPLATE_HASH = '76f6339ddf85e95c55fbd87ad35451e9936619fd8a1bc57d35aea9ac369183be'
NS = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}

def font(run, size=10.5, bold=False, east='宋体'):
    run.font.name = 'Times New Roman'
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.color.rgb = __import__('docx').shared.RGBColor(0, 0, 0)
    run._element.get_or_add_rPr().get_or_add_rFonts().set(qn('w:eastAsia'), east)
    return run

def no_grid(p):
    e = p._p.get_or_add_pPr().find(qn('w:snapToGrid'))
    if e is None:
        e = OxmlElement('w:snapToGrid'); p._p.get_or_add_pPr().append(e)
    e.set(qn('w:val'), '0')

def clear_runs(p):
    for child in list(p._p):
        if child.tag != qn('w:pPr'): p._p.remove(child)

def make_report():
    assert sha256(TEMPLATE.read_bytes()).hexdigest() == TEMPLATE_HASH, 'Template changed; audit it again first'
    doc = Document(TEMPLATE)
    originals = list(doc.paragraphs)
    teacher_table = etree.tostring(doc.tables[0]._tbl)
    teacher = [deepcopy(x) for x in list(doc._element.body)[26:-1]]
    # P26 onwards include two source spacers, the grading heading and its table.
    assert any(x.tag == qn('w:tbl') for x in teacher)
    heading_sources = [deepcopy(originals[i]._p) for i in (22, 23, 24, 25)]
    for name, size, east in [('Title',26,'宋体'),('Heading 1',14,'仿宋'),('Heading 2',12,'仿宋')]:
        if name not in doc.styles: doc.styles.add_style(name, WD_STYLE_TYPE.PARAGRAPH)
        s=doc.styles[name]; s.base_style=doc.styles['Normal']; s.font.size=Pt(size); s.font.bold=True
        s.font.name='Times New Roman'; s.element.get_or_add_rPr().get_or_add_rFonts().set(qn('w:eastAsia'),east)
        s.paragraph_format.keep_with_next=True
        s.paragraph_format.space_before=Pt(0); s.paragraph_format.space_after=Pt(6)
    originals[1].style=doc.styles['Title']; originals[2].style=doc.styles['Title']
    # Preserve the template's metadata line count, font and overall indentation.
    for index, label, value in [(10,'组长学号姓名:','2024302131052 徐晨帆'),
                                (12,'组员学号姓名:','2024302131051 林尚直'),
                                (13,'','2024302131021 周臣'),
                                (14,'','2024302051136 任思远')]:
        p=originals[index]; clear_runs(p)
        p.paragraph_format.left_indent=Pt(86.4 if label else 210)
        p.paragraph_format.first_line_indent=Pt(0)
        p.paragraph_format.tab_stops.clear_all(); p.paragraph_format.tab_stops.add_tab_stop(Pt(210))
        p.paragraph_format.line_spacing=1
        p.paragraph_format.space_after=Pt(0)
        if label: font(p.add_run(label+'\t'),16,True)
        font(p.add_run(value),16,True).underline=True
    # Retain the entire source cover and its section break; rebuild only the body slots.
    body=doc._element.body
    first_heading_index=list(body).index(originals[22]._p)
    for node in list(body)[first_heading_index:-1]: body.remove(node)

    def p(text='', role='body', before=False):
        para=doc.add_paragraph()
        pf=para.paragraph_format; pf.line_spacing=1.4; pf.space_after=Pt(6)
        pf.page_break_before=before; pf.widow_control=True; no_grid(para)
        if role=='sub':
            para.style=doc.styles['Heading 2']; font(para.add_run(text),12,True,'仿宋')
            pf.first_line_indent=Pt(0); pf.space_before=Pt(5); pf.keep_with_next=True
        elif role=='caption':
            para.alignment=WD_ALIGN_PARAGRAPH.CENTER; pf.keep_with_next=True
            pf.first_line_indent=Pt(0); font(para.add_run(text),10.5)
        else:
            pf.first_line_indent=Pt(21); font(para.add_run(text),10.5)
        return para

    def main_heading(i, before=True):
        node=deepcopy(heading_sources[i]); body.insert(len(body)-1,node)
        from docx.text.paragraph import Paragraph
        para=Paragraph(node,doc._body); para.style=doc.styles['Heading 1']
        para.paragraph_format.page_break_before=before
        para.paragraph_format.keep_with_next=True; para.paragraph_format.space_after=Pt(8)
        no_grid(para)

    def image(stem, caption, max_height=12.0):
        p(caption,'caption')
        para=p(); para.paragraph_format.first_line_indent=Pt(0)
        para.paragraph_format.line_spacing=1; para.alignment=WD_ALIGN_PARAGRAPH.CENTER
        path=FIG/(stem+'.png')
        with Image.open(path) as img: width,height=img.size
        w=min(14.45, max_height*width/height)
        run=para.add_run(); run.add_picture(str(path),width=Cm(w))
        for node in run._r.xpath('.//wp:docPr'): node.set('descr',caption)
        return para

    def table(headers, rows, widths):
        tab=doc.add_table(rows=1, cols=len(headers)); tab.alignment=WD_TABLE_ALIGNMENT.CENTER; tab.autofit=False
        for col,width in zip(tab.columns,widths): col.width=Cm(width)
        for row in [headers]+rows:
            cells=tab.rows[0].cells if row is headers else tab.add_row().cells
            for cell,text,width in zip(cells,row,widths):
                cell.width=Cm(width); cell.vertical_alignment=WD_CELL_VERTICAL_ALIGNMENT.CENTER
                para=cell.paragraphs[0]; no_grid(para)
                para.alignment=WD_ALIGN_PARAGRAPH.LEFT
                para.paragraph_format.line_spacing=1.1; para.paragraph_format.space_after=Pt(3)
                para.paragraph_format.space_before=Pt(3)
                font(para.add_run(text),10, row is headers)
            trPr=cells[0]._tc.getparent().get_or_add_trPr()
            trPr.append(OxmlElement('w:cantSplit'))
        tab.rows[0]._tr.get_or_add_trPr().append(OxmlElement('w:tblHeader'))
        pr=tab._tbl.tblPr
        borders=OxmlElement('w:tblBorders')
        for edge in ['top','left','bottom','right','insideH','insideV']:
            e=OxmlElement('w:'+edge); e.set(qn('w:val'),'single'); e.set(qn('w:sz'),'4'); e.set(qn('w:color'),'000000'); borders.append(e)
        pr.append(borders)
        return tab

    main_heading(0,False)
    p('本项目为 check@collect POI 数据采集及核验应用。系统支持采集者在现场提交照片、设备感知的位置、分类与描述，核验者标识错误并复核整改，第三方工作人员处理异议并形成最终结论。地图、微信身份和文字识别通过外部平台适配器接入，通知采用 RabbitMQ。')
    image('use-cases','图1 关键功能用例图',11.6)
    p('采集者负责采集、补采和说明驳回理由；核验者负责核查、标错和复核；现场裁定仅允许被指派且不是争议当事人的第三方提交。管理员预建账号、分配角色、管理现场任务并发布采集公告。一个账号可以兼有多种业务角色，开发成员代号不作为操作人身份。')
    p('使用环境为手机和电脑浏览器，经 Internet 访问 HTTPS 服务。现场采集依赖用户授予相机或选图权限及设备定位权限。选图与定位分两步进行；当前实现读取的是本次设备位置，未从照片 EXIF 还原旧照片拍摄地点，因此应在 POI 现场完成定位。')
    p('项目由不超过四名成员合作，资源为约 8 GB 内存的 Linux 笔记本。采用单个应用进程和关系数据库，避免为课程规模引入微服务、服务发现或独立工作流平台。主机必须持续开机联网；手机定位精度和真实微信授权效果须实机验收。')

    main_heading(1)
    p('系统选择 B/S、逻辑分层与模块化单体。浏览器负责页面与交互，Spring Boot 控制器接收请求，应用服务承载业务规则，Repository 管理持久化。M1 至 M4 在同一后端应用内通过公开服务和端口协作，共享 PostgreSQL，但各模块负责自己的数据表。')
    p('2.1 包图与模块边界','sub')
    image('packages','图2 四个业务模块的 UML 包图',12.3)
    p('M1 提供账号、角色和本地 userId。M2 管理 POI、不可变采集版本、照片与地图摘要；M3 管理核验、错误单、异议和现场裁定；M4 管理通知、收件人和聊天会话。依赖方向为 M3→M2/M4/M1、M2→M4/M1、M4→M1；跨模块不直接修改对方的数据表。')
    p('分层降低页面、业务和存储变更之间的影响；端口及适配器隔离地图、OCR 和消息传输差异。模块化单体便于四人协作和同库事务，但各模块随同一应用发布，不能分别扩容。当前规模接受这一折衷。')

    p('2.2 核心类图与适配关系','sub',True)
    p('类图选取直接体现模块边界的真实服务和接口，省略控制器、DTO、Repository 及非关键方法；方法参数从略。实线箭头表示持有或调用关系，虚线空心三角表示实现接口。')
    image('class-review','图3 核验服务与 POI 版本端口类图',9.2)
    p('ReviewService 依赖 PoiRevisionPort，PoiRevisionAdapter 将端口调用委托给 PoiService。由 M3 校验任务和当事人，再调用 M2 创建版本，避免 M2 反向依赖错误单状态。两模块共用 IdentityService 的身份与角色检查。')
    image('class-messaging','图4 通知传输策略类图',8.5)
    p('NotificationPublisher 依赖 NotificationTransport。正式部署选择 RabbitNotificationTransport；进程内实现用于联调和测试。地图 MapAdapter 和文字识别 OcrAdapter 采用相同的接口隔离思路。')

    p('2.3 采集与通知顺序图','sub',True)
    image('sequence-collection','图5 采集提交与新数据群发通知',17.5)
    p('浏览器仅在用户明确操作后请求位置，缺照片或定位失败时不提交。M2 创建 POI 和首版元数据，M4 在同一数据库事务中保存通知及创建时的收件人快照，提交后发布通知标识。消息失败不回滚已经成功的采集，失败记录在限定次数内补投。')
    p('M4 在图中概括存储、发布和消费对象的协作。通知记录与收件记录先持久化，消费按通知和收件人幂等更新，避免重复投递形成重复收件结果。照片文件位于持久目录，数据库保存文件标识；文件系统和数据库不是同一事务，异常时可能留下孤立文件，需运维清理。')

    p('2.4 异议与现场裁定顺序图','sub',True)
    image('sequence-dispute','图6 现场裁定与最终有效版本确定',17.5)
    p('争议当事人提交异议后，系统创建现场任务并通知第三方；无可用人员时保留待分配状态。两种裁定都要求新的现场照片与位置作为证据。REPLACED 使用现场版本，ORIGINAL_CORRECT 保留原当前版本，现场证据版本均保留。')
    p('M3 在同一业务事务内完成任务、关闭错误单和异议、指定最终版本并调用 M2 同步地图摘要，再由 M4 通知原采集者与核验者。已完成的裁定不能由当事双方覆盖，新问题另行形成后续处理记录。图中省略 HTTP 控制器和提交后的消息传输细节。')

    p('2.5 质量属性表','sub',True)
    p('质量属性按课程关于功能性、效率、可靠性、安全性、可维护性、可移植性和重用性的思路选取，以可观察的场景说明架构如何支持质量目标。下表的验收口径用于检查设计；尚未执行的性能及实机检查不表述为已达标。')
    p('表1 质量属性表','caption')
    table(['属性编号','质量场景','架构响应','验收口径'],[
        ['Q1 功能正确性与一致性','多人针对同一 POI 修改、复核或提交裁定。','不可变版本、预期版本校验、行锁及同库事务；显式保存最终版本。','旧版本冲突被拒绝；地图摘要、错误单状态和最终版本一致。'],
        ['Q2 安全性','普通账号尝试越权操作、读取私聊或绑定他人微信。','M1 身份与角色检查；业务参与人鉴权；CSRF 与安全会话；微信票据绑定会话且一次消费。','越权访问返回 403；退出后拒绝访问；微信绑定唯一约束有效。'],
        ['Q3 可靠性与可用性','RabbitMQ 暂时不可达或重复投递通知。','业务与通知记录先入库；提交后发布；限次重试与幂等投递。','业务提交不因发布失败撤销；恢复后可补投；重复消息不重复收件。'],
        ['Q4 效率','手机查询分类列表并调用地图或 OCR。','列表分页；浏览器加载地图；OCR 独立 CPU 容器并限制并发。','检查分页及资源占用；目标为普通列表 2 秒内响应，需另行测量，不含外部平台耗时。'],
        ['Q5 可维护性与可扩充性','更换地图、OCR 或通知传输实现。','稳定端口和适配器；四模块职责明确；数据库迁移分号段管理。','业务不直接拼供应商协议；可替换实现并运行相关回归测试。'],
        ['Q6 可移植性与重用性','换主机部署或在整改、现场任务中复用采集。','响应式 Web、Docker Compose、外置配置及持久卷；采集表单和 POI 版本服务复用。','同一工程可在配置完整的主机启动；重建容器保留数据；两类流程复用真实照片提交。'],
        ['Q7 易用性','用户拒绝定位、换照片或设备位置明显异常。','先选图再授权定位；保留草稿；换图取消旧定位和 OCR；显示精度、距离及中文状态。','未授权不采集；旧响应不覆盖新照片；异常导航起点需核对，错误可重试。'],
    ],[2.0,3.3,4.5,4.8])
    p('质量之间存在折衷：重试和持久化增加存储与实现成本；单机部署降低部署开销，也形成单点故障。当前使用持久卷、健康检查和自动重启改善恢复能力，不宣称具备高可用集群。')

    p('2.6 映射关系表','sub',True)
    p('映射关系连接功能需求、质量目标、UML 中的架构元素与代码入口，使“需要做什么、由谁实现、如何检查”可以追溯。代码根目录为 backend/src/main/java/edu/sadp/checkcollect；表内类名位于相应模块包，页面位于 frontend/src。')
    p('表2 需求与架构实现的映射关系表','caption')
    table(['需求及质量','架构元素','主要实现入口','检查方式'],[
        ['F1 账号、角色、微信身份\nQ2 Q7','M1 identity；图2、图3','IdentityService、WechatController、MiniWechatController；/api/me','独立账号得到不同 userId；角色、绑定唯一性和退出限制；真实微信另做实测。'],
        ['F2 采集、照片与补采\nQ1 Q6 Q7','M2 poi；图2、图3、图5','PoiService、PhotoStorage、PoiCollectView；POST /api/pois','照片、设备位置、分类描述同次提交；更新形成新版本，历史可读。'],
        ['F3 核验、标错及整改\nQ1 Q2 Q5','M3 review → M2 版本端口；图3','ReviewService、PoiRevisionPort、PoiRevisionAdapter','标错生成错误单；整改后待复核；过期版本及非本人修改被拒绝。'],
        ['F4 异议及现场裁定\nQ1 Q2 Q6','M3、M2、M4；图6','submitDispute、submitFieldResult；现场任务页面','指派人员非当事人；两种裁定均有现场证据，最终版本和双方通知一致。'],
        ['F5 地图与路线导航\nQ4 Q5 Q7','M2 及高德平台；图7','AmapView、AmapRoute、MapAdapter、AmapAdapter','按分类和进展筛选；确认起点后显示路线；WGS84 与 GCJ-02 在平台边界转换。'],
        ['F6 招牌文字识别\nQ4 Q5 Q7','M2 及 OCR 服务；图7','OcrAdapter、PaddleOcrAdapter；/api/pois/ocr','真实图片得到文字建议；用户确认分类；换图后旧结果不覆盖新图。'],
        ['F7 公告、群发及点发\nQ2 Q3','M4 messaging；图4、图5','MessagingService、NotificationPublisher、NotificationTransport','按角色或 userId 投递；离线后可读；失败可补投、重复消费幂等。'],
        ['F8 私聊与角色群\nQ2 Q3 Q6','M4 会话与成员边界；图2','ConversationRepository、MessagingService、MessagesView','错误单当事人进入同一私聊；其他用户无权读取；两个角色群独立保留历史。'],
    ],[2.9,2.8,4.2,4.7])
    p('包图说明系统组织，类图说明端口与实现关系，顺序图说明事务和协作顺序；表1与表2分别约束质量目标及需求落点。该描述与课程多视图建模思路一致，并将原组件职责、接口说明作为实现细节保留在仓库文档中。')

    main_heading(2)
    p('前端采用 Vue 3 与 TypeScript，使用路由和登录态控制入口；后端采用 Java 17、Spring Boot 和 PostgreSQL，Flyway 执行 V1、V200、V201、V300、V400 迁移；消息采用 RabbitMQ，图片文字识别采用独立 PaddleOCR CPU 服务。开发工具为 Git、GitHub、Maven、npm、Docker Compose，UML 使用 PlantUML。')
    image('deployment','图7 应用运行节点部署图',12.2)
    p('实际运行节点为 Linux 笔记本。app 提供页面和 API，数据库、消息与 OCR 通过容器内部网络访问；照片、数据库、RabbitMQ 数据及模型使用持久卷。cloudflared 主动建立加密隧道，将固定 HTTPS 入口 collect.allwayso.top 路由至本机应用。密钥保存在部署配置中，不写入文档和源码。')
    p('M1 提供网站应用和小程序两类微信身份入口，按平台 appId 与 openId 查询绑定，成功后进入相应本地 userId；每个账号仍只保存一条微信绑定，不依据昵称合并身份。小程序使用绑定浏览器会话的短期一次性票据。票据保存在单实例内存，重启使未完成票据失效；多实例需要共享票据存储。真实平台配置、主体权限及实机登录以验收结果为准。')
    p('已有集成记录包含真实 PostgreSQL 业务测试、RabbitMQ 失败恢复和实际 OCR 识别；地图与路线服务也有浏览器联调记录。手机现场 GPS 精度、微信授权绑定及主机断电恢复仍须按演示脚本复验，不以测试坐标代替现场证据。正式演示前应记录源码和镜像版本。')

    main_heading(3)
    p('本组四人按业务模块纵向分工，每位成员覆盖本模块页面、服务接口、数据及验证。组长为徐晨帆。最终贡献比例由团队确认均为 25%，合计 100%；业务账号角色可以兼任，不等同于开发分工。')
    for text in [
        '林尚直，学号 2024302131051，成员 A，贡献 25%。负责 M1 用户与应用入口、前后端公共骨架、账号角色接口，以及集成修复、部署与运行说明。',
        '任思远，学号 2024302051136，成员 B，贡献 25%。负责 M2 POI 采集与版本、照片、地图与导航、OCR 辅助及向其他模块提供版本服务。',
        '徐晨帆，学号 2024302131052，成员 C，组长，贡献 25%。负责 M3 核验、错误单与整改状态机、异议、第三方现场任务和最终裁定，以及演示材料整理。',
        '周臣，学号 2024302131021，成员 D，贡献 25%。负责 M4 RabbitMQ 通知、角色群发及点发、私聊与群聊、失败重试与去重及消息联调。',
    ]: p(text)
    p('团队以公开接口、独立测试账号和模块测试完成协作，通过集成场景检查跨模块版本、状态与通知的一致性。后续维护按数据归属定位责任模块，跨模块修改同时检查表2列出的相关场景。')
    p('参考课程材料：第2章软件质量中质量属性及其竞争关系；第3章软件体系结构模型；第4章层次化与 B/S 风格；第5章 UML 中类图、包图、顺序图和部署图的表达方法。项目需求依据 desc.txt，实现依据本报告对应的 PR7 源码。')
    for node in teacher: body.insert(len(body)-1,node)
    assert len(doc.sections)==2
    assert etree.tostring(doc.tables[-1]._tbl)==teacher_table
    buf=BytesIO(); doc.save(buf); candidate=ZipFile(BytesIO(buf.getvalue()))
    with ZipFile(TEMPLATE) as original:
        parts={name:original.read(name) for name in original.namelist()}
        original_styles=etree.fromstring(parts['word/styles.xml'])
        style_ids={s.get(qn('w:styleId')) for s in original_styles.findall(qn('w:style'))}
        for style in etree.fromstring(candidate.read('word/styles.xml')).findall(qn('w:style')):
            if style.get(qn('w:styleId')) not in style_ids: original_styles.append(deepcopy(style))
        parts['word/styles.xml']=etree.tostring(original_styles,xml_declaration=True,encoding='UTF-8',standalone=True)
        for name in ['word/document.xml','word/_rels/document.xml.rels','[Content_Types].xml']:
            parts[name]=candidate.read(name)
        for name in candidate.namelist():
            if name.startswith('word/media/'): parts[name]=candidate.read(name)
        with ZipFile(OUTPUT,'w',ZIP_DEFLATED) as target:
            for name,data in parts.items(): target.writestr(name,data)
        editable={'word/document.xml','word/_rels/document.xml.rels','[Content_Types].xml','word/styles.xml'}
        with ZipFile(OUTPUT) as target:
            preserved=[name for name in original.namelist() if name not in editable]
            assert all(original.read(name)==target.read(name) for name in preserved)
            assert target.testzip() is None
    result=Document(OUTPUT)
    assert len(result.sections)==2 and len(result.tables)==3
    assert sha256(TEMPLATE.read_bytes()).hexdigest()==TEMPLATE_HASH
    for s,t in zip(Document(TEMPLATE).sections,result.sections):
        assert etree.tostring(s._sectPr)==etree.tostring(t._sectPr)
    print(json.dumps({'output':str(OUTPUT),'sections':len(result.sections),'tables':len(result.tables),
                      'figures':len(result.inline_shapes),'preserved_package_parts':len(preserved),
                      'template_sha256':TEMPLATE_HASH},ensure_ascii=False))

if __name__=='__main__': make_report()
