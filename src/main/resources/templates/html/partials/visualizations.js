/**
 * Advanced Visualizations for jstall HTML reports
 * Uses D3.js for interactive timeline, heat maps, graphs, and flow diagrams
 */

(function() {
    'use strict';

    // ========== D3.js Loader ==========

    let d3Loaded = false;
    const D3_CDN = 'https://cdn.jsdelivr.net/npm/d3@7.8.5/dist/d3.min.js';

    function loadD3(callback) {
        if (d3Loaded && window.d3) {
            callback();
            return;
        }

        // Check if D3 is already loaded
        if (window.d3) {
            d3Loaded = true;
            callback();
            return;
        }

        // Load D3.js dynamically
        const script = document.createElement('script');
        script.src = D3_CDN;
        script.onload = () => {
            d3Loaded = true;
            callback();
        };
        script.onerror = () => {
            console.error('Failed to load D3.js');
            // Fallback: show message to user
            document.querySelectorAll('.d3-visualization').forEach(el => {
                el.innerHTML = '<div style="padding: 20px; background: #fff3cd; border-radius: 4px;">' +
                              '<strong>⚠ Visualization Unavailable</strong><br>' +
                              'D3.js could not be loaded. Please check your internet connection.</div>';
            });
        };
        document.head.appendChild(script);
    }

    // ========== Interactive Timeline ==========

    function createInteractiveTimeline(containerId, data) {
        const container = document.getElementById(containerId);
        if (!container) return;

        loadD3(() => {
            renderTimeline(container, data);
        });
    }

    function renderTimeline(container, data) {
        // Clear existing content
        container.innerHTML = '';

        const margin = {top: 40, right: 30, bottom: 60, left: 120};
        const width = container.clientWidth - margin.left - margin.right;
        const height = Math.max(400, data.threads.length * 30) - margin.top - margin.bottom;

        const svg = d3.select(container)
            .append('svg')
            .attr('width', width + margin.left + margin.right)
            .attr('height', height + margin.top + margin.bottom)
            .append('g')
            .attr('transform', `translate(${margin.left},${margin.top})`);

        // Title
        svg.append('text')
            .attr('x', width / 2)
            .attr('y', -20)
            .attr('text-anchor', 'middle')
            .style('font-size', '16px')
            .style('font-weight', 'bold')
            .text('Thread State Timeline');

        // X scale (time/dumps)
        const dumpCount = data.dumpCount || 5;
        const xScale = d3.scaleLinear()
            .domain([0, dumpCount - 1])
            .range([0, width]);

        // Y scale (threads)
        const yScale = d3.scaleBand()
            .domain(data.threads.map(t => t.name))
            .range([0, height])
            .padding(0.1);

        // Color scale for states
        const colorScale = d3.scaleOrdinal()
            .domain(['RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'NEW', 'TERMINATED'])
            .range(['#28a745', '#dc3545', '#ffc107', '#fd7e14', '#17a2b8', '#6c757d']);

        // Add X axis
        const xAxis = d3.axisBottom(xScale)
            .ticks(dumpCount)
            .tickFormat(d => `Dump ${Math.floor(d + 1)}`);

        svg.append('g')
            .attr('transform', `translate(0,${height})`)
            .call(xAxis)
            .selectAll('text')
            .attr('transform', 'rotate(-45)')
            .style('text-anchor', 'end');

        // Add Y axis
        svg.append('g')
            .call(d3.axisLeft(yScale));

        // Add grid lines
        svg.append('g')
            .attr('class', 'grid')
            .attr('opacity', 0.1)
            .call(d3.axisLeft(yScale).tickSize(-width).tickFormat(''));

        // Tooltip
        const tooltip = d3.select(container)
            .append('div')
            .style('position', 'absolute')
            .style('visibility', 'hidden')
            .style('background', 'white')
            .style('border', '1px solid #ddd')
            .style('border-radius', '4px')
            .style('padding', '8px')
            .style('font-size', '12px')
            .style('box-shadow', '0 2px 4px rgba(0,0,0,0.2)')
            .style('pointer-events', 'none')
            .style('z-index', '1000');

        // Draw timeline segments
        data.threads.forEach(thread => {
            const stateHistory = thread.stateHistory || [];

            stateHistory.forEach((state, idx) => {
                if (idx < dumpCount - 1) {
                    const x1 = xScale(idx);
                    const x2 = xScale(idx + 1);
                    const y = yScale(thread.name);
                    const segmentWidth = x2 - x1;

                    svg.append('rect')
                        .attr('x', x1)
                        .attr('y', y)
                        .attr('width', segmentWidth)
                        .attr('height', yScale.bandwidth())
                        .attr('fill', colorScale(state))
                        .attr('stroke', 'white')
                        .attr('stroke-width', 1)
                        .style('cursor', 'pointer')
                        .on('mouseover', function(event) {
                            d3.select(this).attr('opacity', 0.7);
                            tooltip
                                .style('visibility', 'visible')
                                .html(`<strong>${thread.name}</strong><br>` +
                                      `Dump ${idx + 1} → ${idx + 2}<br>` +
                                      `State: <span style="color: ${colorScale(state)}">${state}</span>`);
                        })
                        .on('mousemove', function(event) {
                            tooltip
                                .style('top', (event.pageY - 10) + 'px')
                                .style('left', (event.pageX + 10) + 'px');
                        })
                        .on('mouseout', function() {
                            d3.select(this).attr('opacity', 1);
                            tooltip.style('visibility', 'hidden');
                        });
                }
            });
        });

        // Add legend
        const legend = svg.append('g')
            .attr('transform', `translate(0, ${height + 45})`);

        const states = ['RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING'];
        states.forEach((state, i) => {
            const legendItem = legend.append('g')
                .attr('transform', `translate(${i * 120}, 0)`);

            legendItem.append('rect')
                .attr('width', 15)
                .attr('height', 15)
                .attr('fill', colorScale(state));

            legendItem.append('text')
                .attr('x', 20)
                .attr('y', 12)
                .style('font-size', '12px')
                .text(state);
        });
    }

    // ========== Thread State Heat Map ==========

    function createThreadStateHeatMap(containerId, data) {
        const container = document.getElementById(containerId);
        if (!container) return;

        loadD3(() => {
            renderHeatMap(container, data);
        });
    }

    function renderHeatMap(container, data) {
        container.innerHTML = '';

        const margin = {top: 40, right: 30, bottom: 60, left: 120};
        const cellSize = 40;
        const width = Math.max(600, data.dumpCount * cellSize) + margin.left + margin.right;
        const height = data.threads.length * cellSize + margin.top + margin.bottom;

        const svg = d3.select(container)
            .append('svg')
            .attr('width', width)
            .attr('height', height)
            .append('g')
            .attr('transform', `translate(${margin.left},${margin.top})`);

        // Title
        svg.append('text')
            .attr('x', (width - margin.left - margin.right) / 2)
            .attr('y', -20)
            .attr('text-anchor', 'middle')
            .style('font-size', '16px')
            .style('font-weight', 'bold')
            .text('Thread State Heat Map');

        // Color scale based on state severity
        const stateValue = {
            'RUNNABLE': 1,
            'TIMED_WAITING': 2,
            'WAITING': 3,
            'BLOCKED': 4,
            'NEW': 0,
            'TERMINATED': 0
        };

        const colorScale = d3.scaleSequential()
            .domain([0, 4])
            .interpolator(d3.interpolateRgb('#28a745', '#dc3545'));

        // Tooltip
        const tooltip = d3.select(container)
            .append('div')
            .style('position', 'absolute')
            .style('visibility', 'hidden')
            .style('background', 'white')
            .style('border', '1px solid #ddd')
            .style('border-radius', '4px')
            .style('padding', '8px')
            .style('font-size', '12px')
            .style('box-shadow', '0 2px 4px rgba(0,0,0,0.2)')
            .style('pointer-events', 'none')
            .style('z-index', '1000');

        // Draw heat map cells
        data.threads.forEach((thread, threadIdx) => {
            const stateHistory = thread.stateHistory || [];

            stateHistory.forEach((state, dumpIdx) => {
                const value = stateValue[state] || 0;

                svg.append('rect')
                    .attr('x', dumpIdx * cellSize)
                    .attr('y', threadIdx * cellSize)
                    .attr('width', cellSize - 2)
                    .attr('height', cellSize - 2)
                    .attr('fill', colorScale(value))
                    .attr('stroke', 'white')
                    .attr('stroke-width', 2)
                    .style('cursor', 'pointer')
                    .on('mouseover', function(event) {
                        d3.select(this)
                            .attr('stroke', '#333')
                            .attr('stroke-width', 3);
                        tooltip
                            .style('visibility', 'visible')
                            .html(`<strong>${thread.name}</strong><br>` +
                                  `Dump ${dumpIdx + 1}<br>` +
                                  `State: ${state}`);
                    })
                    .on('mousemove', function(event) {
                        tooltip
                            .style('top', (event.pageY - 10) + 'px')
                            .style('left', (event.pageX + 10) + 'px');
                    })
                    .on('mouseout', function() {
                        d3.select(this)
                            .attr('stroke', 'white')
                            .attr('stroke-width', 2);
                        tooltip.style('visibility', 'hidden');
                    });
            });
        });

        // Add column labels (dumps)
        for (let i = 0; i < data.dumpCount; i++) {
            svg.append('text')
                .attr('x', i * cellSize + cellSize / 2)
                .attr('y', -10)
                .attr('text-anchor', 'middle')
                .style('font-size', '12px')
                .text(`D${i + 1}`);
        }

        // Add row labels (threads)
        data.threads.forEach((thread, idx) => {
            svg.append('text')
                .attr('x', -10)
                .attr('y', idx * cellSize + cellSize / 2)
                .attr('text-anchor', 'end')
                .attr('alignment-baseline', 'middle')
                .style('font-size', '11px')
                .text(thread.name.length > 20 ? thread.name.substring(0, 18) + '...' : thread.name);
        });

        // Add legend
        const legendWidth = 200;
        const legendHeight = 20;
        const legend = svg.append('g')
            .attr('transform', `translate(${data.dumpCount * cellSize - legendWidth}, ${data.threads.length * cellSize + 20})`);

        const legendScale = d3.scaleLinear()
            .domain([0, 4])
            .range([0, legendWidth]);

        const legendAxis = d3.axisBottom(legendScale)
            .ticks(5)
            .tickFormat(d => {
                const labels = ['OK', 'Waiting', '', 'Blocked', 'Critical'];
                return labels[Math.round(d)] || '';
            });

        // Gradient for legend
        const defs = svg.append('defs');
        const gradient = defs.append('linearGradient')
            .attr('id', 'heatmap-gradient')
            .attr('x1', '0%')
            .attr('x2', '100%');

        gradient.append('stop')
            .attr('offset', '0%')
            .attr('stop-color', '#28a745');

        gradient.append('stop')
            .attr('offset', '100%')
            .attr('stop-color', '#dc3545');

        legend.append('rect')
            .attr('width', legendWidth)
            .attr('height', legendHeight)
            .style('fill', 'url(#heatmap-gradient)');

        legend.append('g')
            .attr('transform', `translate(0, ${legendHeight})`)
            .call(legendAxis);
    }

    // ========== Lock Contention Graph ==========

    function createLockContentionGraph(containerId, data) {
        const container = document.getElementById(containerId);
        if (!container) return;

        loadD3(() => {
            renderLockGraph(container, data);
        });
    }

    function renderLockGraph(container, data) {
        container.innerHTML = '';

        const width = container.clientWidth;
        const height = 600;

        const svg = d3.select(container)
            .append('svg')
            .attr('width', width)
            .attr('height', height);

        // Title
        svg.append('text')
            .attr('x', width / 2)
            .attr('y', 20)
            .attr('text-anchor', 'middle')
            .style('font-size', '16px')
            .style('font-weight', 'bold')
            .text('Lock Contention Graph');

        // Force simulation
        const simulation = d3.forceSimulation(data.nodes)
            .force('link', d3.forceLink(data.links).id(d => d.id).distance(100))
            .force('charge', d3.forceManyBody().strength(-300))
            .force('center', d3.forceCenter(width / 2, height / 2))
            .force('collision', d3.forceCollide().radius(30));

        // Links (waits-for relationships)
        const link = svg.append('g')
            .selectAll('line')
            .data(data.links)
            .enter()
            .append('line')
            .attr('stroke', '#999')
            .attr('stroke-opacity', 0.6)
            .attr('stroke-width', d => Math.sqrt(d.value || 1) * 2);

        // Add arrow markers
        svg.append('defs').selectAll('marker')
            .data(['arrow'])
            .enter().append('marker')
            .attr('id', 'arrow')
            .attr('viewBox', '0 -5 10 10')
            .attr('refX', 25)
            .attr('refY', 0)
            .attr('markerWidth', 6)
            .attr('markerHeight', 6)
            .attr('orient', 'auto')
            .append('path')
            .attr('d', 'M0,-5L10,0L0,5')
            .attr('fill', '#999');

        link.attr('marker-end', 'url(#arrow)');

        // Nodes (threads and locks)
        const node = svg.append('g')
            .selectAll('circle')
            .data(data.nodes)
            .enter()
            .append('circle')
            .attr('r', d => d.type === 'lock' ? 15 : 20)
            .attr('fill', d => d.type === 'lock' ? '#ffc107' : (d.state === 'BLOCKED' ? '#dc3545' : '#667eea'))
            .attr('stroke', '#fff')
            .attr('stroke-width', 2)
            .style('cursor', 'pointer')
            .call(d3.drag()
                .on('start', dragstarted)
                .on('drag', dragged)
                .on('end', dragended));

        // Node labels
        const label = svg.append('g')
            .selectAll('text')
            .data(data.nodes)
            .enter()
            .append('text')
            .text(d => d.name)
            .attr('font-size', 10)
            .attr('dx', 25)
            .attr('dy', 4)
            .style('pointer-events', 'none');

        // Tooltip
        const tooltip = d3.select(container)
            .append('div')
            .style('position', 'absolute')
            .style('visibility', 'hidden')
            .style('background', 'white')
            .style('border', '1px solid #ddd')
            .style('border-radius', '4px')
            .style('padding', '8px')
            .style('font-size', '12px')
            .style('box-shadow', '0 2px 4px rgba(0,0,0,0.2)')
            .style('pointer-events', 'none')
            .style('z-index', '1000');

        node.on('mouseover', function(event, d) {
                d3.select(this).attr('r', d.type === 'lock' ? 18 : 24);
                tooltip
                    .style('visibility', 'visible')
                    .html(`<strong>${d.name}</strong><br>` +
                          `Type: ${d.type}<br>` +
                          (d.state ? `State: ${d.state}<br>` : '') +
                          (d.waiters ? `Waiters: ${d.waiters}` : ''));
            })
            .on('mousemove', function(event) {
                tooltip
                    .style('top', (event.pageY - 10) + 'px')
                    .style('left', (event.pageX + 10) + 'px');
            })
            .on('mouseout', function(event, d) {
                d3.select(this).attr('r', d.type === 'lock' ? 15 : 20);
                tooltip.style('visibility', 'hidden');
            });

        // Update positions on tick
        simulation.on('tick', () => {
            link
                .attr('x1', d => d.source.x)
                .attr('y1', d => d.source.y)
                .attr('x2', d => d.target.x)
                .attr('y2', d => d.target.y);

            node
                .attr('cx', d => d.x)
                .attr('cy', d => d.y);

            label
                .attr('x', d => d.x)
                .attr('y', d => d.y);
        });

        function dragstarted(event, d) {
            if (!event.active) simulation.alphaTarget(0.3).restart();
            d.fx = d.x;
            d.fy = d.y;
        }

        function dragged(event, d) {
            d.fx = event.x;
            d.fy = event.y;
        }

        function dragended(event, d) {
            if (!event.active) simulation.alphaTarget(0);
            d.fx = null;
            d.fy = null;
        }

        // Add legend
        const legend = svg.append('g')
            .attr('transform', `translate(20, ${height - 80})`);

        const legendData = [
            {label: 'Thread (Blocked)', color: '#dc3545'},
            {label: 'Thread (Other)', color: '#667eea'},
            {label: 'Lock', color: '#ffc107'}
        ];

        legendData.forEach((item, i) => {
            const legendItem = legend.append('g')
                .attr('transform', `translate(0, ${i * 25})`);

            legendItem.append('circle')
                .attr('r', 8)
                .attr('fill', item.color);

            legendItem.append('text')
                .attr('x', 15)
                .attr('y', 5)
                .style('font-size', '12px')
                .text(item.label);
        });
    }

    // ========== Sankey Diagram for Thread Flow ==========

    function createSankeyDiagram(containerId, data) {
        const container = document.getElementById(containerId);
        if (!container) return;

        loadD3(() => {
            renderSankey(container, data);
        });
    }

    function renderSankey(container, data) {
        container.innerHTML = '';

        const width = container.clientWidth;
        const height = 600;
        const margin = {top: 20, right: 20, bottom: 20, left: 20};

        const svg = d3.select(container)
            .append('svg')
            .attr('width', width)
            .attr('height', height)
            .append('g')
            .attr('transform', `translate(${margin.left},${margin.top})`);

        // Title
        svg.append('text')
            .attr('x', (width - margin.left - margin.right) / 2)
            .attr('y', -5)
            .attr('text-anchor', 'middle')
            .style('font-size', '16px')
            .style('font-weight', 'bold')
            .text('Thread State Flow (Sankey Diagram)');

        // Simple Sankey-like implementation using D3
        const sankeyWidth = width - margin.left - margin.right;
        const sankeyHeight = height - margin.top - margin.bottom;

        // Calculate node positions
        const nodeWidth = 20;
        const nodePadding = 20;
        const states = ['RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING'];
        const dumpCount = data.dumpCount || 5;

        const xScale = d3.scaleLinear()
            .domain([0, dumpCount - 1])
            .range([0, sankeyWidth - nodeWidth]);

        const colorScale = d3.scaleOrdinal()
            .domain(states)
            .range(['#28a745', '#dc3545', '#ffc107', '#fd7e14']);

        // Build nodes and links
        const nodes = [];
        const links = [];
        const nodeMap = new Map();

        // Create nodes for each state at each dump
        for (let dump = 0; dump < dumpCount; dump++) {
            states.forEach((state, stateIdx) => {
                const nodeId = `${dump}-${state}`;
                const node = {
                    id: nodeId,
                    dump: dump,
                    state: state,
                    value: 0,
                    x: xScale(dump),
                    y: 0  // Will be calculated
                };
                nodes.push(node);
                nodeMap.set(nodeId, node);
            });
        }

        // Create links based on state transitions
        data.transitions.forEach(trans => {
            const sourceId = `${trans.fromDump}-${trans.fromState}`;
            const targetId = `${trans.toDump}-${trans.toState}`;
            const source = nodeMap.get(sourceId);
            const target = nodeMap.get(targetId);

            if (source && target) {
                source.value += trans.count;
                target.value += trans.count;
                links.push({
                    source: source,
                    target: target,
                    value: trans.count
                });
            }
        });

        // Calculate node heights and Y positions
        states.forEach((state, stateIdx) => {
            for (let dump = 0; dump < dumpCount; dump++) {
                const node = nodeMap.get(`${dump}-${state}`);
                if (node) {
                    const maxValue = Math.max(...nodes.filter(n => n.dump === dump).map(n => n.value));
                    node.height = maxValue > 0 ? (node.value / maxValue) * (sankeyHeight / states.length - nodePadding) : 0;
                    node.y = stateIdx * (sankeyHeight / states.length) + nodePadding / 2;
                }
            }
        });

        // Draw links (flows)
        const link = svg.append('g')
            .selectAll('path')
            .data(links)
            .enter()
            .append('path')
            .attr('d', d => {
                const x0 = d.source.x + nodeWidth;
                const x1 = d.target.x;
                const y0 = d.source.y + d.source.height / 2;
                const y1 = d.target.y + d.target.height / 2;
                const xi = d3.interpolateNumber(x0, x1);
                const x2 = xi(0.5);
                return `M${x0},${y0}C${x2},${y0} ${x2},${y1} ${x1},${y1}`;
            })
            .attr('fill', 'none')
            .attr('stroke', d => colorScale(d.source.state))
            .attr('stroke-opacity', 0.3)
            .attr('stroke-width', d => Math.max(1, d.value / 2));

        // Draw nodes
        const node = svg.append('g')
            .selectAll('rect')
            .data(nodes.filter(n => n.value > 0))
            .enter()
            .append('rect')
            .attr('x', d => d.x)
            .attr('y', d => d.y)
            .attr('width', nodeWidth)
            .attr('height', d => d.height)
            .attr('fill', d => colorScale(d.state))
            .attr('stroke', '#fff')
            .attr('stroke-width', 1);

        // Add labels
        const label = svg.append('g')
            .selectAll('text')
            .data(states)
            .enter()
            .append('text')
            .attr('x', -10)
            .attr('y', (d, i) => i * (sankeyHeight / states.length) + (sankeyHeight / states.length) / 2)
            .attr('text-anchor', 'end')
            .attr('alignment-baseline', 'middle')
            .style('font-size', '12px')
            .style('font-weight', 'bold')
            .text(d => d);

        // Add dump labels
        for (let i = 0; i < dumpCount; i++) {
            svg.append('text')
                .attr('x', xScale(i) + nodeWidth / 2)
                .attr('y', sankeyHeight + 15)
                .attr('text-anchor', 'middle')
                .style('font-size', '12px')
                .text(`Dump ${i + 1}`);
        }

        // Tooltip
        const tooltip = d3.select(container)
            .append('div')
            .style('position', 'absolute')
            .style('visibility', 'hidden')
            .style('background', 'white')
            .style('border', '1px solid #ddd')
            .style('border-radius', '4px')
            .style('padding', '8px')
            .style('font-size', '12px')
            .style('box-shadow', '0 2px 4px rgba(0,0,0,0.2)')
            .style('pointer-events', 'none')
            .style('z-index', '1000');

        node.on('mouseover', function(event, d) {
                tooltip
                    .style('visibility', 'visible')
                    .html(`<strong>Dump ${d.dump + 1}</strong><br>` +
                          `State: ${d.state}<br>` +
                          `Threads: ${d.value}`);
            })
            .on('mousemove', function(event) {
                tooltip
                    .style('top', (event.pageY - 10) + 'px')
                    .style('left', (event.pageX + 10) + 'px');
            })
            .on('mouseout', function() {
                tooltip.style('visibility', 'hidden');
            });
    }

    // ========== Public API ==========

    window.jstallVisualizations = {
        createInteractiveTimeline: createInteractiveTimeline,
        createThreadStateHeatMap: createThreadStateHeatMap,
        createLockContentionGraph: createLockContentionGraph,
        createSankeyDiagram: createSankeyDiagram,

        // Initialize all visualizations on page
        initAll: function() {
            // Auto-detect visualization containers
            document.querySelectorAll('[data-viz-type]').forEach(container => {
                const vizType = container.getAttribute('data-viz-type');
                const dataSource = container.getAttribute('data-viz-source');

                if (dataSource && window[dataSource]) {
                    const data = window[dataSource];

                    switch(vizType) {
                        case 'timeline':
                            createInteractiveTimeline(container.id, data);
                            break;
                        case 'heatmap':
                            createThreadStateHeatMap(container.id, data);
                            break;
                        case 'lockgraph':
                            createLockContentionGraph(container.id, data);
                            break;
                        case 'sankey':
                            createSankeyDiagram(container.id, data);
                            break;
                    }
                }
            });
        }
    };

    // Auto-initialize on load
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => {
            window.jstallVisualizations.initAll();
        });
    } else {
        window.jstallVisualizations.initAll();
    }
})();