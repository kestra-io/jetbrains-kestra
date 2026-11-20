// Draws the flow graph with Cytoscape and the dagre layout, as the VS Code extension does.
// The IDE calls window.kestraTopology; clicks go back through window.kestraPost.
(function () {
    cytoscape.use(cytoscapeDagre);

    const message = document.getElementById('message');
    const graphEl = document.getElementById('graph');
    const rotate = document.getElementById('rotate');
    const BUCKETS = ['success', 'failed', 'warning', 'running', 'info', 'neutral', 'pending'];
    const RUN_CLASSES = BUCKETS.map(bucket => 'run-' + bucket).join(' ');

    // Reused across updates so live edits swap elements instead of recreating the view each time.
    let cy;
    let theme = {};
    let rankDir = 'TB';

    // The IDE font may not exist in the embedded browser, so fall back on the system UI font.
    function fontStack() {
        return (theme.font ? JSON.stringify(theme.font) + ', ' : '') + 'system-ui, -apple-system, "Segoe UI", sans-serif';
    }

    function layoutOptions() {
        return {name: 'dagre', rankDir, nodeSep: 25, rankSep: 50, padding: 24, fit: true};
    }

    function graphStyle() {
        return [
            {
                selector: 'node.task',
                style: {
                    'shape': 'round-rectangle',
                    'background-color': theme.surface,
                    'background-image': 'data(icon)',
                    'background-width': '22px',
                    'background-height': '22px',
                    'background-position-x': '12px',
                    'background-position-y': '50%',
                    'background-clip': 'none',
                    'border-color': theme.border,
                    'border-width': 1,
                    'width': 200,
                    'height': 48,
                    'label': 'data(label)',
                    'color': theme.foreground,
                    'font-family': fontStack(),
                    'font-size': 14,
                    'font-weight': 600,
                    'text-valign': 'center',
                    'text-halign': 'center',
                    'text-margin-x': 16,
                    'text-wrap': 'ellipsis',
                    'text-max-width': '130'
                }
            },
            {selector: 'node.boundary', style: {'width': 1, 'height': 1, 'opacity': 0}},
            // One live-state border per bucket.
            ...BUCKETS.map(bucket => ({
                selector: 'node.task.run-' + bucket,
                style: {'border-color': theme[bucket], 'border-width': 2}
            })),
            {
                selector: 'node.cluster',
                style: {
                    'shape': 'round-rectangle',
                    'background-color': theme.cluster,
                    'background-opacity': 0.1,
                    'border-color': theme.cluster,
                    'border-width': 1,
                    'label': 'data(label)',
                    'color': theme.cluster,
                    'font-family': fontStack(),
                    'font-size': 11,
                    'font-weight': 600,
                    'text-background-color': theme.background,
                    'text-background-opacity': 1,
                    'text-background-shape': 'roundrectangle',
                    'text-background-padding': '5',
                    // valign top puts the label above the box, the margin pulls it back inside the padding band.
                    'text-valign': 'top',
                    'text-halign': 'center',
                    'text-margin-y': 24,
                    'padding': '30'
                }
            },
            {
                selector: 'node.cluster.triggers',
                style: {'background-color': theme.triggers, 'border-color': theme.triggers, 'color': theme.triggers}
            },
            {
                selector: 'edge',
                style: {
                    'width': 1.5,
                    'line-color': theme.foreground,
                    'line-style': 'dashed',
                    'line-cap': 'round',
                    'line-dash-pattern': [1, 5],
                    'line-opacity': 0.7,
                    'source-arrow-color': theme.foreground,
                    'source-arrow-shape': 'circle',
                    'source-distance-from-node': 6,
                    'target-arrow-color': theme.foreground,
                    'target-arrow-shape': 'triangle',
                    'target-distance-from-node': 2,
                    'arrow-scale': 0.4,
                    // Orthogonal elbow routing.
                    'curve-style': 'taxi',
                    'taxi-direction': 'auto',
                    'taxi-turn': 24,
                    'taxi-turn-min-distance': 12
                }
            },
            {selector: 'edge.hl', style: {'line-color': theme.running, 'target-arrow-color': theme.running, 'line-opacity': 1, 'width': 2}}
        ];
    }

    function showMessage(text) {
        graphEl.style.display = 'none';
        rotate.style.display = 'none';
        message.style.display = 'block';
        message.textContent = text;
    }

    function render(elements) {
        message.style.display = 'none';
        graphEl.style.display = 'block';
        rotate.style.display = 'block';
        if (cy) {
            cy.batch(() => {
                cy.elements().remove();
                cy.add(elements);
            });
            cy.layout(layoutOptions()).run();
            return;
        }
        cy = cytoscape({container: graphEl, elements, style: graphStyle(), layout: layoutOptions(), wheelSensitivity: 0.3});
        cy.on('tap', 'node.task', event => window.kestraPost(JSON.stringify({type: 'reveal', taskId: event.target.data('taskId')})));
        cy.on('mouseover', 'node.task', event => event.target.connectedEdges().addClass('hl'));
        cy.on('mouseout', 'node.task', event => event.target.connectedEdges().removeClass('hl'));
    }

    // Only the icons change: no layout, so the user's pan and zoom stay.
    function updateIcons(elements) {
        if (!cy) return;
        cy.batch(() => {
            for (const element of elements) {
                if (!element.data || element.data.icon === undefined) continue;
                const node = cy.getElementById(element.data.id);
                if (node.nonempty()) node.data('icon', element.data.icon);
            }
        });
    }

    function setTaskState(taskId, bucket) {
        if (!cy) return;
        cy.nodes('.task').forEach(node => {
            if (node.data('taskId') === taskId) {
                node.removeClass(RUN_CLASSES);
                if (bucket) node.addClass('run-' + bucket);
            }
        });
    }

    function setTheme(value) {
        theme = value;
        const root = document.documentElement.style;
        root.setProperty('--bg', theme.background);
        root.setProperty('--fg', theme.foreground);
        root.setProperty('--muted', theme.muted);
        root.setProperty('--border', theme.border);
        root.setProperty('--surface', theme.surface);
        root.setProperty('--font', fontStack());
        if (cy) cy.style(graphStyle());
    }

    // The tool window is resized and shown later than the page loads: keep the graph fitted to it.
    new ResizeObserver(() => {
        if (cy && graphEl.clientWidth > 0) {
            cy.resize();
            cy.fit(undefined, 24);
        }
    }).observe(graphEl);

    rotate.addEventListener('click', () => {
        rankDir = rankDir === 'LR' ? 'TB' : 'LR';
        if (cy) cy.layout(layoutOptions()).run();
    });

    window.kestraTopology = {
        render,
        updateIcons,
        notice: showMessage,
        setTaskState,
        resetStates: () => cy && cy.nodes('.task').removeClass(RUN_CLASSES),
        setTheme
    };
    window.kestraPost(JSON.stringify({type: 'ready'}));
})();
