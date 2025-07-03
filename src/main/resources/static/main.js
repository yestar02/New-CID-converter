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

    // 호텔명과 최저가를 저장
    const hotelName = res.hotel;
    const cheapestPriceValue = res.cheapest && res.cheapest.price
      ? res.cheapest.price
      : 0;

    // 결과창 타이틀 수정: (호텔이름) – (가격)원
    const priceText = cheapestPriceValue > 0
      ? cheapestPriceValue.toLocaleString() + '원'
      : '가격 정보 없음';
    $('#hotelTitle').textContent = `${hotelName} – ${priceText}`;
    $('#hotelTitle').style.display = 'block';

    // 가격 비교 테이블 구성
    res.priced.forEach(item => {
      const tr = document.createElement('tr');
      const priceDisplay = item.isSoldOut
        ? '매진'
        : item.price.toLocaleString();
      const priceClass = item.isSoldOut ? 'sold-out' : '';
      tr.innerHTML = `
        <td>${item.label}</td>
        <td class="${priceClass}">${priceDisplay}</td>
      `;
      $('#tbl tbody').appendChild(tr);
    });
    $('#tbl').style.display = 'table';

    // 제휴 링크
    res.affiliateLinks.forEach(aff => {
      const li = document.createElement('li');
      li.innerHTML = `<a href="${aff.url}" target="_blank">${aff.label}</a>`;
      $('#affList').appendChild(li);
    });
    $('#aff').style.display = 'block';

  } catch (err) {
    console.error(err);
    alert('오류가 발생했습니다.');
  } finally {
    $('#loading').style.display = 'none';
  }
});
