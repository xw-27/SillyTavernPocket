/*
 * 口袋酒馆桥接插件(pocket-bridge)
 *
 * 作用:APK 原生界面通过本插件控制酒馆。本插件是 APK 与 SillyTavern 之间唯一的接触点。
 * 启用条件:仅在口袋酒馆 APK 的 WebView 中生效(User-Agent 含 SillyTavernPocket)。
 * 在普通浏览器中打开酒馆时,本插件不做任何事情。
 *
 * 约束(为了不影响其它插件):
 *  - 不修改 SillyTavern 的函数、事件处理和设置项;只通过 getContext() 读取,并调用酒馆已有的功能。
 *  - 只订阅事件(eventSource.on),不拦截、不改写事件。
 *  - 不注册斜杠命令、设置面板、菜单按钮。
 *  - 不删除、不移动 DOM 节点。
 *
 * 协议 v1:
 *  - 原生 -> 插件:window.__pocketShell.call(id, cmd, args)
 *  - 插件 -> 原生:PocketNative.onResult(id, json) / PocketNative.onError(id, message)
 *  - 插件 -> 原生事件:PocketNative.onEvent(name)  名称:ready
 *  - 返回的 json 形如 {"ok":true,"data":...} 或 {"ok":false,"error":"..."}
 */
(function () {
    const PROTOCOL = 1;
    const VERSION = '0.1.0';

    if (!navigator.userAgent.includes('SillyTavernPocket') || !window.PocketNative) {
        return;
    }

    const native = window.PocketNative;

    /** 等待酒馆完成初始化(最多约 30 秒) */
    async function waitReady() {
        for (let i = 0; i < 150; i++) {
            try {
                const ctx = SillyTavern.getContext();
                if (ctx.characters && ctx.getRequestHeaders) return ctx;
            } catch (e) {
                // 页面还没初始化
            }
            await new Promise(r => setTimeout(r, 200));
        }
        throw new Error('酒馆页面还没有就绪');
    }

    /**
     * 酒馆的 last_mes 可能是毫秒数字,也可能是字符串日期(如 2026-10-12@17h10m00s 或 ISO 格式)。
     * 统一转换为毫秒;无法解析时返回 0。
     */
    /** 解析 computed 颜色 "rgb(r, g, b)" 或 "rgba(r, g, b, a)";无法解析返回 null。 */
    function parseCss(css) {
        const m = /rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\/\s]+([\d.]+%?))?\s*\)/.exec(css || '');
        if (!m) return null;
        let a = 1;
        if (m[4] !== undefined) a = m[4].endsWith('%') ? parseFloat(m[4]) / 100 : parseFloat(m[4]);
        return { rgb: [+m[1], +m[2], +m[3]], a };
    }

    /** 把半透明颜色叠到不透明底色上，返回 [r, g, b] */
    function composite(c, base) {
        return c.rgb.map((v, i) => v * c.a + base[i] * (1 - c.a));
    }

    /** 读取酒馆当前主题的不透明颜色：背景、文字、强调色。 */
    function readTheme() {
        const bodyStyle = getComputedStyle(document.body);
        const rootStyle = getComputedStyle(document.documentElement);
        const rootBg = parseCss(rootStyle.backgroundColor);
        const bodyBg = parseCss(bodyStyle.backgroundColor);
        let base = rootBg && rootBg.a >= 1 ? rootBg.rgb : null;
        if (bodyBg && bodyBg.a >= 1) base = bodyBg.rgb;
        else if (bodyBg && bodyBg.a > 0 && base) base = composite(bodyBg, base);
        if (!base) throw new Error('无法读取页面背景色');

        const text = parseCss(bodyStyle.color);
        const textRgb = text ? composite(text, base) : [238, 238, 238];

        // 强调色取酒馆的引号色(浅深主题下都是醒目的色),让浏览器解析 var(),支持任何颜色写法
        const probe = document.createElement('span');
        probe.style.color = 'var(--SmartThemeQuoteColor)';
        document.body.appendChild(probe);
        const accent = parseCss(getComputedStyle(probe).color);
        probe.remove();
        const accentRgb = accent ? composite(accent, base) : textRgb;

        const round = rgb => rgb.map(v => Math.round(v));
        return { background: round(base), text: round(textRgb), accent: round(accentRgb) };
    }

    /** 命令表。每个命令都只调用酒馆已有的接口。 */
    const commands = {
        hello: async () => ({ protocol: PROTOCOL, version: VERSION }),

        /** 只读取页面样式,不修改任何内容 */
        theme: async () => {
            await waitReady();
            return readTheme();
        },

        newChat: async () => {
            const ctx = await waitReady();
            // 新建对话和收起配置页必须在同一条插件调用链里完成。
            // 不让原生层等待两个独立回调,避免配置页收起时新建动作被卡住。
            closeOpenPanels(null);
            await ctx.executeSlashCommandsWithOptions('/newchat');
            return {};
        },

        /** 打开酒馆原有的面板。元素保留在页面中,这里只是触发它的点击。 */
        openPanel: async ({ id }) => {
            await waitReady();
            const el = document.getElementById(id);
            if (!el) throw new Error('找不到面板 ' + id);
            const target = el.classList.contains('drawer') ? (el.querySelector('.drawer-icon') || el) : el;
            // 先收起其它已展开的面板,避免遮挡
            closeOpenPanels(target);
            target.click();
            return {};
        },

        /** 收起所有展开的顶部面板(原生抽屉打开、回到首页时调用) */
        closePanels: async () => {
            await waitReady();
            closeOpenPanels(null);
            return {};
        },

        /** 列出酒馆顶部设置栏的全部按钮(id、标题、图标),供原生抽屉菜单使用。
         *  图标是酒馆自己的 Font Awesome 字形,画成白色 PNG,由原生按主题着色。 */
        listPanels: async () => {
            await waitReady();
            await document.fonts.ready;
            // 伪元素 content 可能是转义写法(\\f1de)或字形本身,两种都处理
            const glyphChar = (cs) => {
                const raw = (cs.content || '').replace(/^["']|["']$/g, '');
                if (!raw || raw === 'none' || raw === 'normal') return null;
                const m = /^\\([0-9a-fA-F]{4,6})$/.exec(raw);
                return m ? String.fromCodePoint(parseInt(m[1], 16)) : raw;
            };
            const glyphPng = (el) => {
                const cs = getComputedStyle(el, '::before');
                const ch = glyphChar(cs);
                if (!ch) return null;
                const size = 96;
                const c = document.createElement('canvas');
                c.width = c.height = size;
                const g = c.getContext('2d');
                g.fillStyle = '#ffffff';
                g.font = `${cs.fontWeight} ${size * 0.75}px ${cs.fontFamily}`;
                g.textAlign = 'center';
                g.textBaseline = 'middle';
                g.fillText(ch, size / 2, size / 2);
                return c.toDataURL('image/png');
            };
            // 没有 title 属性的按钮,用这里的名字
            const FALLBACK_TITLES = { 'backgrounds-button': '背景' };
            const panels = [];
            document.querySelectorAll('#top-settings-holder > .drawer').forEach(box => {
                const icon = box.querySelector('.drawer-icon');
                if (!icon) return;
                panels.push({
                    id: icon.id || box.id,
                    title: icon.getAttribute('title') || FALLBACK_TITLES[box.id] || box.id,
                    icon: glyphPng(icon),
                });
            });
            return panels;
        },
    };

    /** 收起展开的顶部面板。except 是不需要收起的开关元素。
     *  只点击面板自己的开关(酒馆原有的开合逻辑),不改动 DOM。 */
    function closeOpenPanels(except) {
        document.querySelectorAll('#top-settings-holder .drawer-content.openDrawer').forEach(panel => {
            const box = panel.closest('.drawer');
            const icon = box && box.querySelector('.drawer-icon');
            if (icon && icon !== except) icon.click();
        });
    }

    async function dispatch(cmd, args) {
        const fn = commands[cmd];
        if (!fn) return { ok: false, error: '未知命令 ' + cmd };
        try {
            return { ok: true, data: await fn(args || {}) };
        } catch (e) {
            return { ok: false, error: String((e && e.message) || e) };
        }
    }

    window.__pocketShell = {
        protocol: PROTOCOL,
        version: VERSION,
        call(id, cmd, args) {
            dispatch(cmd, args).then(
                r => native.onResult(id, JSON.stringify(r)),
                e => native.onError(id, String((e && e.message) || e)),
            );
        },
    };

    // 只订阅事件,不改变酒馆的行为
    (async () => {
        try {
            const ctx = await waitReady();
                                    native.onEvent('ready');
        } catch (e) {
            native.onError(-1, '桥接插件初始化失败:' + ((e && e.message) || e));
        }
    })();

    document.body.classList.add('pocket-shell');
})();
