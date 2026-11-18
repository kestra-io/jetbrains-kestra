// The Kestra documentation view, ported from the VS Code extension: docs pages from the public docs service
// for the instance's version, search, the plugin browser and plugin pages rendered from their schema.
// The IDE fetches the data (window.kestraPost / kestraDocs.resolve) and calls kestraDocs.showPlugin.
(function () {
    // ---------- Host bridge ----------
    let nextId = 0;
    const pending = new Map();

    function request(op, args) {
        const id = ++nextId;
        return new Promise(resolve => {
            pending.set(id, resolve);
            window.kestraPost(JSON.stringify({id, op, args: args || {}}));
        });
    }

    function tell(op, args) {
        window.kestraPost(JSON.stringify({op, args: args || {}}));
    }

    // ---------- Markdown ----------
    const KNOWN_COMPONENTS = new Set(['alert', 'collapse']);
    const FENCE_OPEN = /^(:{2,})([A-Za-z][\w-]*)(\{[^}]*\})?\s*$/;
    const FENCE_CLOSE = /^(:{2,})\s*$/;

    function escapeHtml(text) {
        return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    function highlightYaml(code) {
        return code.split('\n').map(line => {
            if (/^\s*#/.test(line)) {
                return `<span class="hl-comment">${escapeHtml(line)}</span>`;
            }
            const match = /^(\s*(?:-\s+)?)([\w."'[\]{}-]+):(\s.*|$)/.exec(line);
            if (!match) {
                return escapeHtml(line);
            }
            const [, indent, key, rest] = match;
            return `${indent}<span class="hl-key">${escapeHtml(key)}</span>:<span class="hl-value">${escapeHtml(rest)}</span>`;
        }).join('\n');
    }

    const md = window.markdownit({
        html: true,
        linkify: true,
        langPrefix: 'language-',
        highlight: (code, lang) => (lang === 'yaml' || lang === 'yml' ? highlightYaml(code) : '')
    })
        .use(window.markdownitContainer, 'alert', {
            validate: params => /^alert(\{|\s|$)/.test(params.trim()),
            render: (tokens, idx) => {
                if (tokens[idx].nesting === 1) {
                    const type = (/type="(info|warning|danger|success)"/.exec(tokens[idx].info) || [])[1] || 'info';
                    return `<div class="alert ${type}">\n`;
                }
                return '</div>\n';
            }
        })
        .use(window.markdownitContainer, 'collapse', {
            validate: params => /^collapse(\{|\s|$)/.test(params.trim()),
            render: (tokens, idx) => {
                if (tokens[idx].nesting === 1) {
                    const title = (/title="([^"]*)"/.exec(tokens[idx].info) || [])[1] || 'Details';
                    return `<details><summary>${escapeHtml(title)}</summary>\n<div class="collapse-body">\n`;
                }
                return '</div></details>\n';
            }
        });

    function renderMarkdown(markdown) {
        const html = md.render(normalizeMdc(String(markdown).replace(/^---\n[\s\S]*?\n---\n/, '')));
        // Embedded players are replaced by a link.
        const linked = html.replace(/<iframe[^>]*\bsrc="(https?:[^"]*)"[\s\S]*?<\/iframe>/gi, '<p><a class="video-link" href="$1">Watch the video</a></p>');
        return stripAll(linked, /<iframe[\s\S]*?<\/iframe>/gi)
            // Images with repository-relative paths have no reachable URL from here.
            .replace(/<img(?![^>]*\bsrc="https?:)[^>]*\/?>/gi, '');
    }

    function stripAll(input, pattern) {
        let previous;
        let current = input;
        do {
            previous = current;
            current = current.replace(pattern, '');
        } while (current !== previous);
        return current;
    }

    // Rewrites MDC component fences line by line, leaving fenced code blocks untouched.
    function normalizeMdc(markdown) {
        const out = [];
        const stack = [];
        let codeFence = '';
        for (const line of markdown.split('\n')) {
            const fence = (/^\s*(`{3,}|~{3,})/.exec(line) || [])[1];
            if (fence) {
                if (!codeFence) {
                    codeFence = fence;
                } else if (fence[0] === codeFence[0] && fence.length >= codeFence.length) {
                    codeFence = '';
                }
                out.push(line);
                continue;
            }
            if (codeFence) {
                out.push(line);
                continue;
            }
            if (/^import\s.+\sfrom\s+['"].+['"];?\s*$/.test(line)) {
                continue;
            }
            const open = FENCE_OPEN.exec(line);
            if (open) {
                const kept = KNOWN_COMPONENTS.has(open[2]);
                stack.push(kept);
                if (kept) {
                    out.push(`${open[1].padEnd(3, ':')}${open[2]}${open[3] || ''}`);
                }
                continue;
            }
            const close = FENCE_CLOSE.exec(line);
            if (close && stack.length > 0) {
                if (stack.pop()) {
                    out.push(close[1].padEnd(3, ':'));
                }
                continue;
            }
            out.push(line);
        }
        return out.join('\n');
    }

    // ---------- Plugin pages ----------
    function pluginElements(entry) {
        return [...(entry.tasks || []), ...(entry.triggers || []), ...(entry.conditions || [])].filter(element => !element.deprecated);
    }

    const byTitle = (a, b) => (a.title || '').localeCompare(b.title || '');

    // Icons are filled in once visible: the instance serves them one by one. Only the keys of images built here are
    // asked for: the server's HTML can carry <img data-icon="anything"> past the sanitizer, and every key asked for
    // is a request to the instance and a cache entry there.
    const builtIcons = new Set();

    function iconImg(cls, key) {
        if (key) builtIcons.add(key);
        return key ? `<img class="${cls}" data-icon="${escapeHtml(key)}" alt="">` : `<span class="${cls}"></span>`;
    }

    function pageHeader(iconKey, title) {
        return `<div class="plugin-header">${iconKey ? iconImg('plugin-icon', iconKey) : ''}<span class="plugin-name">${escapeHtml(title)}</span></div>`;
    }

    function navRow(nav, iconKey, label, subtitle) {
        // Not a <button>: this HTML goes through the sanitizer below, which strips buttons.
        return `<div class="nav-row" role="button" tabindex="0" data-nav="${escapeHtml(nav)}">`
            + iconImg('row-icon', iconKey)
            + `<span class="row-label">${escapeHtml(label)}${subtitle ? `<span class="row-sub">${escapeHtml(subtitle)}</span>` : ''}</span>`
            + '<span class="chev">›</span></div>';
    }

    function renderPluginList(entries) {
        return entries.filter(entry => !entry.subGroup).sort(byTitle)
            .map(entry => navRow(`group:${entry.name}`, entry.group, entry.title || entry.name || '')).join('');
    }

    // A group with no sub-group (most of them) holds its elements on the root entry itself; one with both lists
    // its sub-groups first, then the root's own elements.
    function renderGroupPage(root, subs) {
        return pageHeader(root.group, root.title || root.name || '')
            + subs.sort(byTitle).map(sub => navRow(`sub:${sub.subGroup}`, sub.subGroup, sub.title || '')).join('')
            + elementRows(root);
    }

    function elementRows(entry) {
        return pluginElements(entry).sort((a, b) => a.cls.localeCompare(b.cls))
            .map(element => navRow(`type:${element.cls}`, element.cls, element.cls.split('.').pop(), element.title)).join('');
    }

    function renderSubPage(sub) {
        return pageHeader(sub.subGroup, sub.title || '') + elementRows(sub);
    }

    const BETA_NOTICE = 'This plugin is currently in beta. While it is considered safe for use, please be aware that its API could change in ways that are not compatible with earlier versions in future releases, or it might become unsupported.';

    function renderPluginDoc(type, schema) {
        const meta = schema.properties || {};
        const name = type.split('.').pop() || type;
        const release = releaseNotesUrl(type);
        const parts = [
            '<div class="plugin-header">' + iconImg('plugin-icon', type)
                + `<span class="plugin-name">${escapeHtml(name)}</span>`
                + (release ? `<a class="release-notes" href="${escapeHtml(release)}">Release notes</a>` : '')
                + '</div>'
        ];
        if (meta.$beta) {
            parts.push(`<div class="alert warning"><p>${BETA_NOTICE}</p></div>`);
        }
        if (meta.title) {
            parts.push(`<div class="plugin-intro">${renderMarkdown(meta.title)}</div>`);
        }
        if (meta.description) {
            parts.push(`<div class="plugin-intro">${renderMarkdown(meta.description)}</div>`);
        }
        parts.push(renderMarkdown('```yaml\ntype: ' + type + '\n```'));
        if (meta.$examples && meta.$examples.length) {
            parts.push(section('Examples', meta.$examples.map(example => renderExample(type, example)).join('<hr>')));
        }
        parts.push(
            section('Properties', propertyList(meta.properties)),
            section('Outputs', propertyList(schema.outputs && schema.outputs.properties)),
            section('Definitions', definitionList(schema.definitions))
        );
        return parts.filter(Boolean).join('\n');
    }

    function releaseNotesUrl(type) {
        const [, , groupId, pluginType] = type.split('.');
        if (!pluginType || pluginType === 'ee' || pluginType === 'secret') {
            return null;
        }
        if (pluginType === 'core') {
            return 'https://github.com/kestra-io/kestra/releases';
        }
        return `https://github.com/kestra-io/${groupId === 'storage' ? 'storage' : 'plugin'}-${pluginType}/releases`;
    }

    function section(title, inner) {
        return inner ? `<details class="section"><summary>${title}</summary><div class="section-body">${inner}</div></details>` : '';
    }

    function renderExample(type, example) {
        if (!example.code) {
            return '';
        }
        const code = example.full ? example.code : `id: ${(type.split('.').pop() || '').toLowerCase()}\ntype: ${type}\n${example.code}`;
        const title = example.title ? renderMarkdown(example.title) : '';
        return `${title}${renderMarkdown('```' + (example.lang || 'yaml') + '\n' + code + '\n```')}`;
    }

    function propertyList(properties) {
        const entries = Object.entries(properties || {})
            .filter(([key]) => !key.startsWith('$'))
            .map(([key, property]) => [key, aggregateAllOf(property)])
            .filter(([, property]) => !property.$deprecated);
        const byKey = ([a], [b]) => a.localeCompare(b);
        const required = entries.filter(([, property]) => property.$required).sort(byKey);
        const optional = entries.filter(([, property]) => !property.$required).sort(byKey);
        return [...required, ...optional].map(propertyRow).join('');
    }

    function aggregateAllOf(property) {
        if (!property.allOf) {
            return property;
        }
        const {allOf, ...rest} = property;
        return Object.assign({}, ...allOf, rest);
    }

    function propertyRow([key, property]) {
        const badges = typeNames(property).map(name => `<span class="type-box">${escapeHtml(name)}</span>`).join('');
        const body = [];
        if (property.title) {
            body.push(renderMarkdown(`**${property.title}**`));
        }
        if (property.description) {
            body.push(renderMarkdown(property.description));
        }
        if (property.default !== undefined) {
            body.push(`<p>Default: <code>${escapeHtml(plain(property.default))}</code></p>`);
        }
        if (property.enum) {
            body.push(`<p>Possible values: ${property.enum.map(value => `<code>${escapeHtml(plain(value))}</code>`).join(' ')}</p>`);
        }
        return `<details class="prop"><summary><code>${escapeHtml(key)}</code>${property.$required ? '<span class="req">*</span>' : ''}`
            + `<span class="spacer"></span>${badges}</summary>`
            + `<div class="prop-body">${body.join('') || '<p class="muted">No description.</p>'}</div></details>`;
    }

    function definitionList(definitions) {
        return Object.entries(definitions || {}).map(([key, definition]) => {
            const name = key.split('_')[0].split('.').pop() || key;
            const body = [
                definition.title ? renderMarkdown(`**${definition.title}**`) : '',
                definition.description ? renderMarkdown(definition.description) : '',
                propertyList(definition.properties)
            ].join('');
            return `<details class="prop"><summary><code>${escapeHtml(name)}</code></summary>`
                + `<div class="prop-body">${body || '<p class="muted">No description.</p>'}</div></details>`;
        }).join('');
    }

    function typeNames(property) {
        if (property.$ref) {
            return [property.$ref.split('/').pop().split('_')[0].split('.').pop() || 'object'];
        }
        const variants = property.anyOf || property.oneOf || property.allOf;
        if (variants) {
            return [...new Set(variants.flatMap(typeNames))];
        }
        if (property.type === 'array') {
            return [`array of ${typeNames(property.items || {}).join(' | ') || 'object'}`];
        }
        return property.type ? [property.type] : ['object'];
    }

    function plain(value) {
        return typeof value === 'string' ? value : JSON.stringify(value);
    }

    // ---------- Doc links ----------
    function resolveDocLink(href, page) {
        if (/^https?:\/\//.test(href)) {
            return {external: href};
        }
        if (href.startsWith('/')) {
            return {external: `https://kestra.io${href}`};
        }
        let relative = href.replace(/(\/|^)\d+?\.(?!\d)/g, '$1').replace(/(?:\/index)?\.mdx?(#.+|$)/, '');
        if (page.isIndex === false) {
            relative = `../${relative}`;
        }
        const segments = page.path.split('/').filter(Boolean);
        for (const part of relative.split('/')) {
            if (part === '..') {
                segments.pop();
            } else if (part && part !== '.') {
                segments.push(part);
            }
        }
        return {docPath: segments.join('/')};
    }

    // ---------- Navigation ----------
    let history = [];
    let page = {path: ''};
    let pluginType = null;
    let showSeq = 0;
    let plugins;
    const definitions = new Map();

    function loadPlugins() {
        plugins = plugins || request('subgroups').then(entries => {
            if (!entries) plugins = undefined;
            return entries;
        });
        return plugins;
    }

    function loadDefinition(type) {
        let cached = definitions.get(type);
        if (!cached) {
            cached = request('definition', {type}).then(definition => {
                if (!definition) definitions.delete(type);
                return definition;
            });
            definitions.set(type, cached);
        }
        return cached;
    }

    async function render(entry) {
        if (entry.kind === 'home') {
            page = {path: ''};
            return {html: renderMarkdown(await request('home')), title: ''};
        }
        if (entry.kind === 'doc') {
            const doc = await request('doc', {path: entry.path});
            if (!doc) return null;
            page = doc;
            return {html: renderMarkdown(doc.markdown), title: doc.title};
        }
        if (entry.kind === 'plugin') {
            const definition = await loadDefinition(entry.type);
            if (!definition) return null;
            page = {path: ''};
            const crumbs = await pluginCrumbs(entry.type);
            if (definition.schema && definition.schema.properties) {
                return {html: renderPluginDoc(entry.type, definition.schema), title: '', crumbs};
            }
            return definition.markdown ? {html: renderMarkdown(definition.markdown), title: entry.type.split('.').pop(), crumbs} : null;
        }
        const entries = await loadPlugins();
        if (!entries) return null;
        page = {path: ''};
        if (entry.kind === 'plugins') {
            return {html: renderPluginList(entries), title: '', crumbs: [{label: 'Plugins'}]};
        }
        if (entry.kind === 'pluginGroup') {
            const root = entries.find(candidate => candidate.name === entry.name && !candidate.subGroup);
            if (!root) return null;
            const subs = entries.filter(candidate => candidate.name === entry.name && candidate.subGroup);
            return {html: renderGroupPage(root, subs), title: '', crumbs: [{label: 'Plugins', nav: 'plugins'}, {label: root.title || entry.name}]};
        }
        const sub = entries.find(candidate => candidate.subGroup === entry.sub);
        if (!sub) return null;
        const root = entries.find(candidate => candidate.name === sub.name && !candidate.subGroup);
        return {
            html: renderSubPage(sub),
            title: '',
            crumbs: [{label: 'Plugins', nav: 'plugins'}, {label: (root && root.title) || sub.name || '', nav: `group:${sub.name}`}, {label: sub.title || ''}]
        };
    }

    async function pluginCrumbs(type) {
        const name = type.split('.').pop() || type;
        const entries = await loadPlugins();
        const owns = candidate => pluginElements(candidate).some(element => element.cls === type);
        const sub = entries && entries.find(candidate => candidate.subGroup && owns(candidate));
        const own = entries && !sub && entries.find(candidate => !candidate.subGroup && owns(candidate));
        if (own) {
            return [{label: 'Plugins', nav: 'plugins'}, {label: own.title || own.name || '', nav: `group:${own.name}`}, {label: name}];
        }
        if (!entries || !sub) {
            return [{label: 'Plugins', nav: 'plugins'}, {label: name}];
        }
        const root = entries.find(candidate => candidate.name === sub.name && !candidate.subGroup);
        return [
            {label: 'Plugins', nav: 'plugins'},
            {label: (root && root.title) || sub.name || '', nav: `group:${sub.name}`},
            {label: sub.title || '', nav: `sub:${sub.subGroup}`},
            {label: name}
        ];
    }

    async function show(entry, push = true) {
        const seq = ++showSeq;
        const view = await render(entry).catch(() => null);
        // A slower earlier render must not overwrite the page shown by a newer action.
        if (seq !== showSeq) return false;
        if (!view) {
            // A task type with no loadable doc keeps the current page.
            if (entry.kind !== 'plugin') notice('Could not load this documentation page.');
            return false;
        }
        if (push) history.push(entry);
        if (entry.kind === 'plugin') pluginType = entry.type;
        display(view);
        return true;
    }

    function navigate(target) {
        if (target === 'plugins') return show({kind: 'plugins'});
        if (target.startsWith('group:')) return show({kind: 'pluginGroup', name: target.slice(6)});
        if (target.startsWith('sub:')) return show({kind: 'pluginSub', sub: target.slice(4)});
        if (target.startsWith('type:')) return show({kind: 'plugin', type: target.slice(5)});
    }

    // ---------- Page ----------
    const back = document.getElementById('back');
    const search = document.getElementById('search');
    const results = document.getElementById('results');
    const crumbBar = document.getElementById('crumbs');
    const heading = document.getElementById('title');
    const content = document.getElementById('content');

    function el(tag, className, text) {
        const element = document.createElement(tag);
        if (className) element.className = className;
        if (text !== undefined) element.textContent = text;
        return element;
    }

    // Server content is rendered with markdown-it's html option on. DOMPurify keeps form controls, image maps
    // and <style> by default: they can submit or navigate the view away, which the click guard below does not see.
    const SANITIZE = {
        FORBID_TAGS: ['form', 'button', 'input', 'textarea', 'select', 'option', 'area', 'map', 'style', 'link', 'meta', 'base'],
        FORBID_ATTR: ['formaction', 'action']
    };

    function display(view) {
        heading.textContent = view.title;
        content.innerHTML = DOMPurify.sanitize(view.html, SANITIZE);
        transformTables();
        addCopyButtons();
        showCrumbs(view.crumbs || []);
        back.disabled = history.length <= 1;
        results.hidden = true;
        window.scrollTo(0, 0);
        observeIcons();
    }

    function notice(text) {
        heading.textContent = '';
        crumbBar.hidden = true;
        content.textContent = text;
    }

    function showCrumbs(crumbs) {
        crumbBar.replaceChildren();
        crumbBar.hidden = crumbs.length === 0;
        crumbs.forEach((crumb, index) => {
            if (index > 0) crumbBar.append(el('span', 'crumb-sep', '›'));
            if (crumb.nav) {
                const link = el('button', 'crumb', crumb.label);
                link.addEventListener('click', () => navigate(crumb.nav));
                crumbBar.append(link);
            } else {
                crumbBar.append(el('span', 'crumb current', crumb.label));
            }
        });
    }

    // Tables become a grid of expandable cards, the first cell is the header.
    function transformTables() {
        const skipLabels = new Set(['description', 'details']);
        for (const table of Array.from(content.querySelectorAll('table'))) {
            const headers = Array.from((table.tHead && table.tHead.rows[0] && table.tHead.rows[0].cells) || []).map(cell => (cell.textContent || '').trim());
            const grid = el('div', 'card-grid');
            for (const row of Array.from((table.tBodies[0] && table.tBodies[0].rows) || [])) {
                const [first, ...rest] = Array.from(row.cells);
                if (!first) continue;
                const card = el('details', 'doc-card');
                const summary = el('summary');
                summary.append(...Array.from(first.childNodes));
                card.append(summary);
                const body = el('div', 'doc-card-body');
                rest.forEach((cell, index) => {
                    const label = headers[index + 1];
                    if (label && !skipLabels.has(label.toLowerCase())) body.append(el('span', 'doc-card-label', label));
                    body.append(...Array.from(cell.childNodes));
                });
                card.append(body);
                grid.append(card);
            }
            if (grid.childElementCount > 0) table.replaceWith(grid);
        }
    }

    const COPY_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>';
    const COPIED_ICON = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20 6L9 17l-5-5"/></svg>';

    function addCopyButtons() {
        for (const pre of Array.from(content.querySelectorAll('pre'))) {
            const button = el('button', 'copy');
            button.title = 'Copy';
            button.innerHTML = COPY_ICON;
            button.addEventListener('click', () => {
                const code = pre.querySelector('code');
                tell('copy', {text: code ? code.textContent : ''});
                button.innerHTML = COPIED_ICON;
                setTimeout(() => (button.innerHTML = COPY_ICON), 1500);
            });
            pre.append(button);
        }
    }

    // ---------- Icons ----------
    const icons = new Map();
    const missed = new Map();
    const MISSED_RETRY_MS = 60000;
    const wanted = new Set();
    let iconTimer;
    const iconObserver = new IntersectionObserver(observed => {
        for (const entry of observed) {
            if (!entry.isIntersecting) continue;
            iconObserver.unobserve(entry.target);
            const key = entry.target.dataset.icon;
            // An icon the instance did not have is asked for again after a while, as the plugin does on its side.
            if (icons.has(key) || Date.now() - (missed.get(key) || 0) < MISSED_RETRY_MS) applyIcon(key);
            else wanted.add(key);
        }
        if (wanted.size && !iconTimer) iconTimer = setTimeout(fetchIcons, 50);
    });

    function observeIcons() {
        content.querySelectorAll('img[data-icon]').forEach(img => {
            if (builtIcons.has(img.dataset.icon)) iconObserver.observe(img);
        });
    }

    async function fetchIcons() {
        iconTimer = undefined;
        const keys = Array.from(wanted);
        wanted.clear();
        const found = (await request('icons', {keys})) || {};
        keys.forEach(key => {
            if (found[key]) {
                icons.set(key, found[key]);
                missed.delete(key);
            } else {
                missed.set(key, Date.now());
            }
            applyIcon(key);
        });
    }

    function applyIcon(key) {
        const src = icons.get(key);
        content.querySelectorAll('img[data-icon]').forEach(img => {
            if (img.dataset.icon !== key) return;
            if (src) img.src = src;
            else img.style.visibility = 'hidden';
        });
    }

    // ---------- Events ----------
    let searchTimer;
    let searchSeq = 0;
    search.addEventListener('input', () => {
        clearTimeout(searchTimer);
        const q = search.value.trim();
        if (q.length < 3) {
            results.hidden = true;
            return;
        }
        searchTimer = setTimeout(async () => {
            const seq = ++searchSeq;
            const items = (await request('search', {q})) || [];
            // Only the latest query may render, and not once the query was cleared.
            if (seq === searchSeq && search.value.trim().length >= 3) showResults(items);
        }, 250);
    });
    search.addEventListener('keydown', event => {
        if (event.key === 'Escape') results.hidden = true;
    });

    function showResults(items) {
        results.replaceChildren();
        for (const item of items) {
            const row = el('button', 'result', item.title);
            row.addEventListener('click', () => {
                results.hidden = true;
                search.value = '';
                show({kind: 'doc', path: item.path});
            });
            results.append(row);
        }
        if (items.length === 0) results.append(el('div', 'no-results', 'No results.'));
        results.hidden = false;
    }

    back.addEventListener('click', async () => {
        const previous = history[history.length - 2];
        if (previous && await show(previous, false)) {
            history.pop();
            back.disabled = history.length <= 1;
        }
    });

    // Rows that are not buttons are activated from the keyboard like one.
    document.addEventListener('keydown', event => {
        const row = event.target.closest && event.target.closest('.nav-row[data-nav]');
        if (row && (event.key === 'Enter' || event.key === ' ')) {
            event.preventDefault();
            navigate(row.dataset.nav);
        }
    });

    // Every link is handled here: the embedded browser must never navigate away from the page.
    document.addEventListener('click', event => {
        const target = event.target;
        const anchor = target.closest('a');
        // Before the row branch: server markdown can put a data-nav on an anchor, DOMPurify keeps data-*, and the
        // anchor would still navigate the view to its href.
        if (anchor) event.preventDefault();
        const row = target.closest('[data-nav]');
        if (row && row.dataset.nav) {
            navigate(row.dataset.nav);
            return;
        }
        if (!anchor) return;
        // An anchor inside an <svg> keeps its target in xlink:href, which the view must not follow either.
        const href = anchor.getAttribute('href') || anchor.getAttribute('xlink:href');
        if (!href) return;
        if (href.startsWith('#')) {
            const id = decodeURIComponent(href.slice(1));
            const anchorTarget = document.getElementById(id) || document.getElementsByName(id)[0];
            if (anchorTarget) anchorTarget.scrollIntoView();
            return;
        }
        const link = resolveDocLink(href, page);
        if (link.external) tell('open', {url: link.external});
        else show({kind: 'doc', path: link.docPath});
    });

    function setTheme(theme) {
        Object.entries(theme).forEach(([name, value]) => document.documentElement.style.setProperty(`--${name}`, value));
    }

    window.kestraDocs = {
        resolve(id, value) {
            const resolve = pending.get(id);
            pending.delete(id);
            if (resolve) resolve(value);
        },
        setTheme,
        // From the cursor: only a new type replaces the page, and an unknown one keeps it.
        showPlugin(type) {
            if (type && type !== pluginType) show({kind: 'plugin', type});
        },
        home: () => show({kind: 'home'})
    };
    tell('ready');
})();
