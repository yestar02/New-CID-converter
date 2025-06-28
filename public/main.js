disableDevtool({ disableMenu: true }); // 개발자도구 차단

const $ = sel => document.querySelector(sel);
const copy = txt => navigator.clipboard.writeText(txt);

$('#frm').addEventListener('submit', async e => {
  e.preventDefault();
  const url = $('#agodaUrl').value.trim();
  if (!url) return alert('URL을 입력하세요.');

  $('#hotelTitle').style.display = 'none';
  $('#tbl').style.display = 'none';
  $('#cheapest').innerHTML = '';
  $('#tbl tbody').innerHTML = '';
  $('#affiliateLinks').style.display = 'none';
  $('#affiliateBody').innerHTML = '';

  try {
    const res = await fetch('/convert', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url })
    }).then(r => r.json());

    $('#hotelTitle').textContent = `${res.hotel} – 가격 비교`;
    $('#hotelTitle').style.display = 'block';

    res.priced.forEach(row => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${row.label}</td>
        <td>${row.price.toLocaleString()}</td>
        <td><a href="${row.url}" target="_blank">열기</a></td>
        <td><button data-url="${row.url}">복사</button></td>`;
      $('#tbl tbody').appendChild(tr);
    });

    if (res.cheapest) {
      $('#cheapest').innerHTML = `
        <td>최저가</td>
        <td>${res.cheapest.price.toLocaleString()}</td>
        <td><a href="${res.cheapest.url}" target="_blank">열기</a></td>
        <td><button data-url="${res.cheapest.url}">복사</button></td>`;
    }

    res.affiliateLinks.forEach(link => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${link.label}</td>
        <td><a href="${link.url}" target="_blank">바로가기</a></td>`;
      $('#affiliateBody').appendChild(tr);
    });

    $('#tbl').style.display = 'table';
    $('#affiliateLinks').style.display = 'block';

    document.querySelectorAll('button[data-url]').forEach(btn => {
      btn.onclick = () => copy(btn.dataset.url);
    });
  } catch (err) {
    console.error(err);
    alert('변환 실패 또는 서버 오류');
  }
});
