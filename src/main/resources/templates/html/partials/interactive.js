/**
 * Interactive features for jstall HTML reports
 * Provides collapsible sections, search/filter, sortable tables, and export
 */

(function() {
    'use strict';

    // ========== Collapsible Sections ==========

    function initCollapsibleSections() {
        // Add collapsible functionality to all .card elements with data-collapsible
        document.querySelectorAll('.card[data-collapsible]').forEach(card => {
            const header = card.querySelector('h2, h3');
            if (!header) return;

            header.style.cursor = 'pointer';
            header.style.userSelect = 'none';

            // Add collapse indicator
            const indicator = document.createElement('span');
            indicator.className = 'collapse-indicator';
            indicator.textContent = '▼';
            indicator.style.marginLeft = '10px';
            indicator.style.fontSize = '0.8em';
            indicator.style.transition = 'transform 0.3s';
            header.appendChild(indicator);

            const content = card.querySelector('.card-content, table, .chart-row, p, ul, .stat-grid');
            if (!content) return;

            header.addEventListener('click', () => {
                const isCollapsed = content.style.display === 'none';
                content.style.display = isCollapsed ? '' : 'none';
                indicator.style.transform = isCollapsed ? 'rotate(0deg)' : 'rotate(-90deg)';
            });
        });

        // Add expand/collapse all button
        const toolbar = document.querySelector('.toolbar');
        if (toolbar) {
            const expandAllBtn = document.createElement('button');
            expandAllBtn.className = 'toolbar-button';
            expandAllBtn.textContent = 'Expand All';
            expandAllBtn.onclick = () => {
                document.querySelectorAll('.card[data-collapsible] .collapse-indicator').forEach(ind => {
                    const content = ind.closest('.card').querySelector('.card-content, table, .chart-row, p, ul, .stat-grid');
                    if (content) {
                        content.style.display = '';
                        ind.style.transform = 'rotate(0deg)';
                    }
                });
            };

            const collapseAllBtn = document.createElement('button');
            collapseAllBtn.className = 'toolbar-button';
            collapseAllBtn.textContent = 'Collapse All';
            collapseAllBtn.onclick = () => {
                document.querySelectorAll('.card[data-collapsible] .collapse-indicator').forEach(ind => {
                    const content = ind.closest('.card').querySelector('.card-content, table, .chart-row, p, ul, .stat-grid');
                    if (content) {
                        content.style.display = 'none';
                        ind.style.transform = 'rotate(-90deg)';
                    }
                });
            };

            toolbar.appendChild(expandAllBtn);
            toolbar.appendChild(collapseAllBtn);
        }
    }

    // ========== Search/Filter ==========

    function initSearchFilter() {
        const toolbar = document.querySelector('.toolbar');
        if (!toolbar) return;

        const searchContainer = document.createElement('div');
        searchContainer.className = 'search-container';
        searchContainer.style.marginLeft = 'auto';

        const searchInput = document.createElement('input');
        searchInput.type = 'text';
        searchInput.placeholder = 'Search threads, methods, locks...';
        searchInput.className = 'search-input';
        searchInput.style.padding = '8px 12px';
        searchInput.style.border = '1px solid #ddd';
        searchInput.style.borderRadius = '4px';
        searchInput.style.width = '300px';

        const clearBtn = document.createElement('button');
        clearBtn.className = 'toolbar-button';
        clearBtn.textContent = '✕';
        clearBtn.title = 'Clear search';
        clearBtn.onclick = () => {
            searchInput.value = '';
            filterContent('');
        };

        searchInput.addEventListener('input', (e) => {
            filterContent(e.target.value.toLowerCase());
        });

        searchContainer.appendChild(searchInput);
        searchContainer.appendChild(clearBtn);
        toolbar.appendChild(searchContainer);
    }

    function filterContent(query) {
        if (!query) {
            // Show all rows
            document.querySelectorAll('tr[data-searchable]').forEach(row => {
                row.style.display = '';
            });
            document.querySelectorAll('.card[data-searchable]').forEach(card => {
                card.style.display = '';
            });
            return;
        }

        // Filter table rows
        document.querySelectorAll('tr[data-searchable]').forEach(row => {
            const text = row.textContent.toLowerCase();
            row.style.display = text.includes(query) ? '' : 'none';
        });

        // Filter cards
        document.querySelectorAll('.card[data-searchable]').forEach(card => {
            const text = card.textContent.toLowerCase();
            card.style.display = text.includes(query) ? '' : 'none';
        });
    }

    // ========== Sortable Tables ==========

    function initSortableTables() {
        document.querySelectorAll('table.sortable, table.trend-table').forEach(table => {
            const headers = table.querySelectorAll('thead th');

            headers.forEach((header, index) => {
                header.style.cursor = 'pointer';
                header.style.userSelect = 'none';
                header.title = 'Click to sort';

                // Add sort indicator
                const indicator = document.createElement('span');
                indicator.className = 'sort-indicator';
                indicator.style.marginLeft = '5px';
                indicator.style.opacity = '0.3';
                indicator.textContent = '⇅';
                header.appendChild(indicator);

                let ascending = true;
                header.addEventListener('click', () => {
                    sortTable(table, index, ascending);
                    ascending = !ascending;

                    // Update indicators
                    headers.forEach(h => {
                        const ind = h.querySelector('.sort-indicator');
                        if (ind) ind.textContent = '⇅';
                    });
                    indicator.textContent = ascending ? '⇅' : '⇵';
                    indicator.style.opacity = '1';
                });
            });
        });
    }

    function sortTable(table, columnIndex, ascending) {
        const tbody = table.querySelector('tbody');
        const rows = Array.from(tbody.querySelectorAll('tr'));

        rows.sort((a, b) => {
            const aCell = a.cells[columnIndex];
            const bCell = b.cells[columnIndex];
            if (!aCell || !bCell) return 0;

            const aText = aCell.textContent.trim();
            const bText = bCell.textContent.trim();

            // Try numeric sort first
            const aNum = parseFloat(aText.replace(/[^0-9.-]/g, ''));
            const bNum = parseFloat(bText.replace(/[^0-9.-]/g, ''));

            if (!isNaN(aNum) && !isNaN(bNum)) {
                return ascending ? aNum - bNum : bNum - aNum;
            }

            // Fallback to string sort
            return ascending ? aText.localeCompare(bText) : bText.localeCompare(aText);
        });

        // Re-append rows in sorted order
        rows.forEach(row => tbody.appendChild(row));
    }

    // ========== Export Functions ==========

    function initExport() {
        const toolbar = document.querySelector('.toolbar');
        if (!toolbar) return;

        const exportBtn = document.createElement('button');
        exportBtn.className = 'toolbar-button';
        exportBtn.textContent = '↓ Export';

        const exportMenu = document.createElement('div');
        exportMenu.className = 'export-menu';
        exportMenu.style.display = 'none';
        exportMenu.style.position = 'absolute';
        exportMenu.style.background = 'white';
        exportMenu.style.border = '1px solid #ddd';
        exportMenu.style.borderRadius = '4px';
        exportMenu.style.boxShadow = '0 2px 8px rgba(0,0,0,0.1)';
        exportMenu.style.zIndex = '1000';

        const exportOptions = [
            { label: 'Export as CSV', action: exportAsCSV },
            { label: 'Export as JSON', action: exportAsJSON },
            { label: 'Copy findings to clipboard', action: copyFindings }
        ];

        exportOptions.forEach(opt => {
            const btn = document.createElement('button');
            btn.textContent = opt.label;
            btn.className = 'export-option';
            btn.style.display = 'block';
            btn.style.width = '100%';
            btn.style.padding = '8px 16px';
            btn.style.border = 'none';
            btn.style.background = 'none';
            btn.style.textAlign = 'left';
            btn.style.cursor = 'pointer';
            btn.onmouseover = () => btn.style.background = '#f5f5f5';
            btn.onmouseout = () => btn.style.background = 'none';
            btn.onclick = () => {
                opt.action();
                exportMenu.style.display = 'none';
            };
            exportMenu.appendChild(btn);
        });

        exportBtn.onclick = () => {
            exportMenu.style.display = exportMenu.style.display === 'none' ? 'block' : 'none';
            const rect = exportBtn.getBoundingClientRect();
            exportMenu.style.top = (rect.bottom + 5) + 'px';
            exportMenu.style.left = rect.left + 'px';
        };

        // Close menu when clicking outside
        document.addEventListener('click', (e) => {
            if (!exportBtn.contains(e.target) && !exportMenu.contains(e.target)) {
                exportMenu.style.display = 'none';
            }
        });

        const exportContainer = document.createElement('div');
        exportContainer.style.position = 'relative';
        exportContainer.appendChild(exportBtn);
        exportContainer.appendChild(exportMenu);
        toolbar.appendChild(exportContainer);
    }

    function exportAsCSV() {
        const tables = document.querySelectorAll('table');
        if (tables.length === 0) {
            alert('No tables to export');
            return;
        }

        let csv = '';
        tables.forEach((table, index) => {
            if (index > 0) csv += '\n\n';

            const title = table.closest('.card')?.querySelector('h2, h3')?.textContent || `Table ${index + 1}`;
            csv += `"${title}"\n`;

            // Headers
            const headers = Array.from(table.querySelectorAll('thead th')).map(th => {
                const text = th.textContent.replace(/[⇅⇵]/g, '').trim();
                return `"${text}"`;
            });
            csv += headers.join(',') + '\n';

            // Rows
            table.querySelectorAll('tbody tr').forEach(row => {
                if (row.style.display === 'none') return; // Skip filtered rows
                const cells = Array.from(row.cells).map(cell => {
                    const text = cell.textContent.trim().replace(/"/g, '""');
                    return `"${text}"`;
                });
                csv += cells.join(',') + '\n';
            });
        });

        downloadFile(csv, 'jstall-export.csv', 'text/csv');
    }

    function exportAsJSON() {
        const data = {
            title: document.querySelector('h1')?.textContent || 'jstall Analysis',
            timestamp: new Date().toISOString(),
            findings: [],
            tables: []
        };

        // Export findings
        document.querySelectorAll('.finding').forEach(finding => {
            const severity = finding.querySelector('.severity-badge')?.textContent || 'INFO';
            const message = finding.querySelector('.finding-message')?.textContent || '';
            data.findings.push({ severity, message });
        });

        // Export tables
        document.querySelectorAll('table').forEach(table => {
            const title = table.closest('.card')?.querySelector('h2, h3')?.textContent || 'Table';
            const headers = Array.from(table.querySelectorAll('thead th')).map(th =>
                th.textContent.replace(/[⇅⇵]/g, '').trim()
            );
            const rows = [];
            table.querySelectorAll('tbody tr').forEach(row => {
                if (row.style.display === 'none') return;
                const rowData = {};
                row.cells.forEach((cell, i) => {
                    rowData[headers[i] || `col${i}`] = cell.textContent.trim();
                });
                rows.push(rowData);
            });
            data.tables.push({ title, headers, rows });
        });

        downloadFile(JSON.stringify(data, null, 2), 'jstall-export.json', 'application/json');
    }

    function copyFindings() {
        const findings = [];
        document.querySelectorAll('.finding').forEach(finding => {
            const severity = finding.querySelector('.severity-badge')?.textContent || 'INFO';
            const message = finding.querySelector('.finding-message')?.textContent || '';
            findings.push(`[${severity}] ${message}`);
        });

        const text = findings.join('\n');
        navigator.clipboard.writeText(text).then(() => {
            alert('Findings copied to clipboard!');
        }).catch(() => {
            // Fallback for older browsers
            const textarea = document.createElement('textarea');
            textarea.value = text;
            document.body.appendChild(textarea);
            textarea.select();
            document.execCommand('copy');
            document.body.removeChild(textarea);
            alert('Findings copied to clipboard!');
        });
    }

    function downloadFile(content, filename, mimeType) {
        const blob = new Blob([content], { type: mimeType });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    }

    // ========== Permalink Support ==========

    function initPermalinks() {
        // Add IDs to all sections
        document.querySelectorAll('.card').forEach((card, index) => {
            const header = card.querySelector('h2, h3');
            if (header && !card.id) {
                const id = header.textContent.toLowerCase()
                    .replace(/[^a-z0-9]+/g, '-')
                    .replace(/^-|-$/g, '');
                card.id = id || `section-${index}`;
            }
        });

        // Handle hash navigation
        if (window.location.hash) {
            const target = document.querySelector(window.location.hash);
            if (target) {
                target.scrollIntoView({ behavior: 'smooth' });
                target.style.background = '#fff3cd';
                setTimeout(() => {
                    target.style.transition = 'background 1s';
                    target.style.background = '';
                }, 2000);
            }
        }

        // Add copy link buttons to headers
        document.querySelectorAll('.card h2, .card h3').forEach(header => {
            const card = header.closest('.card');
            if (!card.id) return;

            const linkBtn = document.createElement('button');
            linkBtn.textContent = '🔗';
            linkBtn.title = 'Copy link to this section';
            linkBtn.className = 'permalink-btn';
            linkBtn.style.marginLeft = '10px';
            linkBtn.style.fontSize = '0.7em';
            linkBtn.style.padding = '2px 6px';
            linkBtn.style.border = '1px solid #ddd';
            linkBtn.style.borderRadius = '3px';
            linkBtn.style.background = 'white';
            linkBtn.style.cursor = 'pointer';
            linkBtn.style.opacity = '0.5';
            linkBtn.onmouseover = () => linkBtn.style.opacity = '1';
            linkBtn.onmouseout = () => linkBtn.style.opacity = '0.5';
            linkBtn.onclick = (e) => {
                e.stopPropagation();
                const url = window.location.href.split('#')[0] + '#' + card.id;
                navigator.clipboard.writeText(url).then(() => {
                    linkBtn.textContent = '✓';
                    setTimeout(() => linkBtn.textContent = '🔗', 1000);
                });
            };

            header.appendChild(linkBtn);
        });
    }

    // ========== Expandable Stack Traces ==========

    function initExpandableStackTraces() {
        document.querySelectorAll('code[data-full-stack]').forEach(code => {
            const fullStack = code.getAttribute('data-full-stack');
            if (!fullStack) return;

            const shortStack = code.textContent;
            code.style.cursor = 'pointer';
            code.title = 'Click to expand full stack trace';

            let expanded = false;
            code.addEventListener('click', () => {
                if (expanded) {
                    code.textContent = shortStack;
                    code.style.whiteSpace = 'nowrap';
                    code.style.overflow = 'hidden';
                    code.style.textOverflow = 'ellipsis';
                } else {
                    code.textContent = fullStack;
                    code.style.whiteSpace = 'pre-wrap';
                    code.style.overflow = 'visible';
                    code.style.textOverflow = 'clip';
                }
                expanded = !expanded;
            });
        });
    }

    // ========== Initialize All Features ==========

    function init() {
        // Wait for DOM to be ready
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
            return;
        }

        // Create toolbar if it doesn't exist
        if (!document.querySelector('.toolbar')) {
            const container = document.querySelector('.container');
            if (container) {
                const toolbar = document.createElement('div');
                toolbar.className = 'toolbar';
                toolbar.style.cssText = 'display: flex; gap: 10px; margin-bottom: 20px; padding: 10px; background: #f5f5f5; border-radius: 4px; align-items: center;';
                container.insertBefore(toolbar, container.firstChild);
            }
        }

        initCollapsibleSections();
        initSearchFilter();
        initSortableTables();
        initExport();
        initPermalinks();
        initExpandableStackTraces();

        console.log('jstall interactive features initialized');
    }

    // Auto-initialize
    init();
})();