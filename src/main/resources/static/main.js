// src/main/resources/static/main.js

// 개발자도구 차단
if (typeof DisableDevtool !== 'undefined') {
  DisableDevtool({ disableMenu: true, clearLog: true });
}

const $ = selector => document.querySelector(selector);
const copy = text => navigator.clipboard.writeText(text);

$('#frm').addEventListener('submit', async e => {
  e.preventDefault();
  const url = $('#agodaUrl').value.trim();
  if (!url) return alert('URL을 입력하세요.');

  $('#loading').style.display = 'block';
  ['#hotelTitle', '#tbl', '#aff'].forEach(sel => $(sel).style.display = 'none');
  $('#tbl tbody').innerHTML = '';
  $('#cheapest').innerHTML = '';
  $('#affList').innerHTML = '';

  try {
    const res = await fetch('/api/convert', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url })
    }).then(r => r.json());

    if (res.success === false) {
      alert(res.message);
      return;
    }

    // 호텔 이름과 API 응답의 cheapestPrice를 사용해 타이틀 설정
    const hotelName = res.hotel;
    const cheapestPrice = res.cheapest && res.cheapest.price > 0
      ? res.cheapest.price.toLocaleString() + '원'
      : '가격 정보 없음';
    $('#hotelTitle').textContent = `${hotelName} – ${cheapestPrice}`;
    $('#hotelTitle').style.display = 'block';

    res.priced.forEach(item => {
      const tr = document.createElement('tr');
      const priceDisplay = item.isSoldOut ? '매진' : item.price.toLocaleString();
      const priceClass = item.isSoldOut ? 'sold-out' : '';
      tr.innerHTML = `
        <td>${item.label}</td>
        <td class="${priceClass}">${priceDisplay}</td>
        <td><a href="${item.url}" target="_blank">열기</a></td>
        <td><button data-url="${item.url}">복사</button></td>`;
      $('#tbl tbody').appendChild(tr);
    });

    const available = res.priced.filter(i => !i.isSoldOut && i.price > 0);
    if (available.length) {
      const best = available[0];
      $('#cheapest').innerHTML = `
        <td>🏆 최저가</td>
        <td>${best.price.toLocaleString()}</td>
        <td><a href="${best.url}" target="_blank">열기</a></td>
        <td><button data-url="${best.url}">복사</button></td>`;
    } else {
      $('#cheapest').innerHTML = `
        <td colspan="4" class="no-available">모든 객실이 매진입니다.</td>`;
    }

    $('#tbl').style.display = 'table';

    res.affiliateLinks.forEach(link => {
      const li = document.createElement('li');
      li.innerHTML = `<a href="${link.url}" target="_blank">${link.label}</a>`;
      $('#affList').appendChild(li);
    });
    $('#aff').style.display = 'block';

    document.querySelectorAll('button[data-url]').forEach(btn =>
      btn.addEventListener('click', () => copy(btn.dataset.url))
    );

  } catch (err) {
    console.error(err);
    alert('오류가 발생했습니다.');
  } finally {
    $('#loading').style.display = 'none';
  }
});
