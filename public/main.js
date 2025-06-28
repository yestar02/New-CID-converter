// 개발자도구 차단
if (typeof DisableDevtool !== 'undefined') {
  DisableDevtool({ disableMenu: true, clearLog: true });
}

const $ = s => document.querySelector(s);
const copy = t => navigator.clipboard.writeText(t);

$('#frm').addEventListener('submit', async e => {
  e.preventDefault();
  const url = $('#agodaUrl').value.trim();
  if (!url) return alert('URL을 입력하세요.');

  $('#loading').style.display = 'block';
  $('#hotelTitle, #tbl, #aff').style.display = 'none';
  $('#tbl tbody').innerHTML = '';
  $('#cheapest').innerHTML = '';
  $('#affList').innerHTML = '';

  try {
    const res = await fetch('/convert', {
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ url })
    }).then(r => r.json());

    if (res.hotel) {
      $('#hotelTitle').textContent = `${res.hotel} – 가격 비교`;
      $('#hotelTitle').style.display = 'block';
    }

    res.priced.forEach(item => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${item.label}</td>
        <td>${item.price.toLocaleString()}</td>
        <td><a href="${item.url}" target="_blank">열기</a></td>
        <td><button data-url="${item.url}">복사</button></td>`;
      $('#tbl tbody').appendChild(tr);
    });

    if (res.cheapest) {
      $('#cheapest').innerHTML = `
        <td>최저가</td>
        <td>${res.cheapest.price.toLocaleString()}</td>
        <td><a href="${res.cheapest.url}" target="_blank">열기</a></td>
        <td><button data-url="${res.cheapest.url}">복사</button></td>`;
    }
    $('#tbl').style.display = 'table';

    res.affiliateLinks.forEach(link => {
      const li = document.createElement('li');
      li.innerHTML = `<a href="${link.url}" target="_blank">${link.label}</a>`;
      $('#affList').appendChild(li);
    });
    $('#aff').style.display = 'block';

    document.querySelectorAll('button[data-url]').forEach(b => {
      b.addEventListener('click', () => copy(b.dataset.url));
    });
  } catch (err) {
    console.error(err);
    alert('오류가 발생했습니다.');
  } finally {
    $('#loading').style.display = 'none';
  }
});
