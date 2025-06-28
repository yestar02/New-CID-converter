/* ---------- 개발자도구 차단 ---------- */
disableDevtool({ disableMenu: true }); // F12·Ctrl+Shift+I·우클릭 차단[25][28]

/* ---------- 헬퍼 ---------- */
const $ = sel => document.querySelector(sel);
const copy = txt => navigator.clipboard.writeText(txt);

/* ---------- 이벤트 ---------- */
$('#frm').addEventListener('submit', async e => {
  e.preventDefault();
  const url = $('#agodaUrl').value.trim();
  if (!url) return alert('URL을 입력하세요.');

  $('#hotelTitle').style.display = 'none';
  $('#tbl').style.display        = 'none';
  $('#tbl tbody').innerHTML      = '';
  $('#cheapest').innerHTML       = '';

  try {
    const res = await fetch('/convert', {
      method : 'POST',
      headers: { 'Content-Type':'application/json' },
      body   : JSON.stringify({ url })
    }).then(r => r.json());

    /* 호텔명 */
    $('#hotelTitle').textContent   = `${res.hotel} – 가격 비교`;
    $('#hotelTitle').style.display = 'block';

    /* 목록 행 */
    res.priced.forEach(row => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${row.label}</td>
        <td>${row.price.toLocaleString()}</td>
        <td><a href="${row.url}" target="_blank">열기</a></td>
        <td><button data-url="${row.url}">복사</button></td>`;
      $('#tbl tbody').appendChild(tr);
    });

    /* 최저가 */
    if (res.cheapest) {
      $('#cheapest').innerHTML =
        `<td>최저가</td>
         <td>${res.cheapest.price.toLocaleString()}</td>
         <td><a href="${res.cheapest.url}" target="_blank">열기</a></td>
         <td><button data-url="${res.cheapest.url}">복사</button></td>`;
    }

    /* 복사 버튼 핸들러 */
    document.querySelectorAll('button[data-url]')
      .forEach(btn => btn.onclick = () => copy(btn.dataset.url));

    $('#tbl').style.display = 'table';
  } catch (err) {
    console.error(err);
    alert('변환 실패 또는 서버 오류');
  }
});
