(function () {
    const $ = (id) => document.getElementById(id);

    async function api(path, opts) {
        opts = opts || {};
        const init = { method: opts.method || 'GET', credentials: 'same-origin' };
        if (opts.body) {
            init.headers = { 'Content-Type': 'application/json' };
            init.body = JSON.stringify(opts.body);
        }
        const res = await fetch(path, init);
        if (res.status === 401) { location.href = '/login'; throw new Error('unauthorized'); }
        return res.json();
    }

    // ---- 标签切换 ----
    document.querySelectorAll('.tab').forEach((btn) => {
        btn.addEventListener('click', () => {
            document.querySelectorAll('.tab').forEach((b) => b.classList.remove('active'));
            btn.classList.add('active');
            const name = btn.dataset.tab;
            ['settings', 'history', 'calendar'].forEach((n) => {
                $('tab-' + n).classList.toggle('hidden', n !== name);
            });
            if (name === 'history') loadHistory();
            if (name === 'calendar') loadSummaryDates().then(renderCalendar);
        });
    });

    // ---- 工具 ----
    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, (c) => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
        }[c]));
    }
    function inline(s) {
        return escapeHtml(s).replace(/\*\*(.+?)\*\*/g, '<b>$1</b>');
    }
    function renderMarkdown(md) {
        const lines = String(md || '').split('\n');
        let html = '', listOpen = false;
        const closeList = () => { if (listOpen) { html += '</ul>'; listOpen = false; } };
        for (const raw of lines) {
            const t = raw.trim();
            if (t.startsWith('### ')) { closeList(); html += '<h3>' + inline(t.slice(4)) + '</h3>'; }
            else if (t.startsWith('## ')) { closeList(); html += '<h2>' + inline(t.slice(3)) + '</h2>'; }
            else if (t.startsWith('# ')) { closeList(); html += '<h1>' + inline(t.slice(2)) + '</h1>'; }
            else if (t.startsWith('- ') || t.startsWith('* ')) { if (!listOpen) { html += '<ul>'; listOpen = true; } html += '<li>' + inline(t.slice(2)) + '</li>'; }
            else if (t === '') { closeList(); }
            else { closeList(); html += '<p>' + inline(t) + '</p>'; }
        }
        closeList();
        return html;
    }
    function fmtTime(ms) {
        const s = Math.floor(ms / 1000);
        return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
    }
    function renderTranscript(data) {
        const segs = (data && data.segments) || [];
        if (!segs.length) return '<div class="empty">该日暂无逐字转写原文</div>';
        let html = '<div class="transcript">';
        segs.forEach((s) => {
            const spk = Number(s.speaker) || 0;
            html += '<div class="seg">' +
                '<span class="seg-time">' + fmtTime(s.start) + '</span>' +
                '<span class="seg-speaker s' + (spk % 6) + '">说话人' + s.speaker + '</span>' +
                '<span class="seg-text">' + escapeHtml(s.text) + '</span>' +
                '</div>';
        });
        html += '</div>';
        return html;
    }

    // ---- 模型设置 ----
    async function loadSettings() {
        try {
            const s = await api('/api/settings');
            const a = s.asr || {};
            const l = s.llm || {};
            $('asrProvider').value = a.provider || 'dashscope';
            $('asrBaseUrl').value = a.base_url || '';
            $('asrKey').value = a.api_key || '';
            $('asrModel').value = a.model || 'paraformer-v2';
            $('asrLanguage').value = a.language || 'zh';
            $('asrDiarization').checked = !!a.diarization;
            $('asrSpeakerCount').value = a.speaker_count || 0;
            $('llmBaseUrl').value = l.base_url || '';
            $('llmKey').value = l.api_key || '';
            $('llmModel').value = l.model || 'qwen-plus';
        } catch (e) { /* ignore */ }
    }

    $('btnSaveSettings').addEventListener('click', async () => {
        const payload = {
            asr: {
                provider: $('asrProvider').value,
                base_url: $('asrBaseUrl').value.trim(),
                api_key: $('asrKey').value.trim(),
                model: $('asrModel').value.trim(),
                language: $('asrLanguage').value.trim(),
                diarization: $('asrDiarization').checked,
                speaker_count: parseInt($('asrSpeakerCount').value) || 0,
                timestamps: true
            },
            llm: {
                base_url: $('llmBaseUrl').value.trim(),
                api_key: $('llmKey').value.trim(),
                model: $('llmModel').value.trim()
            }
        };
        try {
            await api('/api/settings', { method: 'POST', body: payload });
            $('settingsMsg').textContent = '✓ 已保存';
            setTimeout(() => { $('settingsMsg').textContent = ''; }, 2000);
        } catch (e) {
            $('settingsMsg').textContent = '保存失败';
        }
    });

    // ---- 总结历史 ----
    let currentDate = null;
    const transcriptCache = {};

    async function loadHistory() {
        const list = $('historyList');
        const d = await api('/api/summaries');
        list.innerHTML = '';
        if (!d.dates || d.dates.length === 0) {
            list.innerHTML = '<div class="empty">暂无总结，等云端流水线运行后这里会显示</div>';
            return;
        }
        d.dates.forEach((date) => {
            const li = document.createElement('li');
            li.textContent = date;
            li.addEventListener('click', () => showDetail(date));
            list.appendChild(li);
        });
    }

    async function loadTranscriptInto(date, el) {
        if (transcriptCache[date] !== undefined) { el.innerHTML = transcriptCache[date]; return; }
        try {
            const d = await api('/api/transcripts/' + date);
            const html = renderTranscript(d);
            transcriptCache[date] = html;
            el.innerHTML = html;
        } catch (e) { el.innerHTML = '<div class="empty">加载失败</div>'; }
    }

    async function showDetail(date) {
        currentDate = date;
        const d = await api('/api/summaries/' + date);
        $('detailTitle').textContent = date;
        $('detailContent').innerHTML = renderMarkdown(d.content);
        $('detailContent').classList.remove('hidden');
        $('detailTranscript').classList.add('hidden');
        $('btnToggleTranscript').textContent = '查看转写原文';
        $('historyList').classList.add('hidden');
        $('historyDetail').classList.remove('hidden');
    }

    $('btnBack').addEventListener('click', () => {
        $('historyDetail').classList.add('hidden');
        $('historyList').classList.remove('hidden');
    });

    $('btnToggleTranscript').addEventListener('click', async () => {
        if (!currentDate) return;
        const showT = $('detailTranscript').classList.contains('hidden');
        if (showT) {
            await loadTranscriptInto(currentDate, $('detailTranscript'));
            $('detailContent').classList.add('hidden');
            $('detailTranscript').classList.remove('hidden');
            $('btnToggleTranscript').textContent = '查看总结';
        } else {
            $('detailTranscript').classList.add('hidden');
            $('detailContent').classList.remove('hidden');
            $('btnToggleTranscript').textContent = '查看转写原文';
        }
    });

    // ---- 日历 ----
    let calYear, calMonth;
    let summaryDates = new Set();

    async function loadSummaryDates() {
        const d = await api('/api/summaries');
        summaryDates = new Set(d.dates || []);
    }

    function renderCalendar() {
        $('calTitle').textContent = calYear + '年' + (calMonth + 1) + '月';
        const firstDow = new Date(calYear, calMonth, 1).getDay();
        const days = new Date(calYear, calMonth + 1, 0).getDate();
        let html = '';
        for (let i = 0; i < firstDow; i++) html += '<span class="cal-cell empty"></span>';
        for (let d = 1; d <= days; d++) {
            const ds = calYear + '-' + String(calMonth + 1).padStart(2, '0') + '-' + String(d).padStart(2, '0');
            const has = summaryDates.has(ds);
            html += '<span class="cal-cell ' + (has ? 'has' : '') + '"' +
                (has ? ' data-date="' + ds + '"' : '') + '>' + d + '</span>';
        }
        $('calBody').innerHTML = html;
        document.querySelectorAll('.cal-cell.has').forEach((el) => {
            el.addEventListener('click', () => showCalDetail(el.dataset.date));
        });
    }

    async function showCalDetail(date) {
        currentDate = date;
        const d = await api('/api/summaries/' + date);
        $('calDetailTitle').textContent = date;
        $('calDetailContent').innerHTML = renderMarkdown(d.content);
        $('calDetailContent').classList.remove('hidden');
        $('calDetailTranscript').classList.add('hidden');
        $('btnCalToggleTranscript').textContent = '查看转写原文';
        $('calBody').classList.add('hidden');
        $('calDetail').classList.remove('hidden');
    }

    $('btnCalBack').addEventListener('click', () => {
        $('calDetail').classList.add('hidden');
        $('calBody').classList.remove('hidden');
    });

    $('btnCalToggleTranscript').addEventListener('click', async () => {
        if (!currentDate) return;
        const showT = $('calDetailTranscript').classList.contains('hidden');
        if (showT) {
            await loadTranscriptInto(currentDate, $('calDetailTranscript'));
            $('calDetailContent').classList.add('hidden');
            $('calDetailTranscript').classList.remove('hidden');
            $('btnCalToggleTranscript').textContent = '查看总结';
        } else {
            $('calDetailTranscript').classList.add('hidden');
            $('calDetailContent').classList.remove('hidden');
            $('btnCalToggleTranscript').textContent = '查看转写原文';
        }
    });

    $('calPrev').addEventListener('click', () => {
        calMonth--; if (calMonth < 0) { calMonth = 11; calYear--; } renderCalendar();
    });
    $('calNext').addEventListener('click', () => {
        calMonth++; if (calMonth > 11) { calMonth = 0; calYear++; } renderCalendar();
    });

    // 初始化
    const now = new Date();
    calYear = now.getFullYear();
    calMonth = now.getMonth();
    loadSettings();
})();
