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
    const res = await fetch('/api/convert', {
      method:'POST',
      headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ url })
    }).then(r => r.json());

    // 에러 응답 처리
    if (!res.success) {
      alert(res.message);
      return;
    }

    if (res.hotel) {
      $('#hotelTitle').textContent = `${res.hotel} – 가격 비교`;
      $('#hotelTitle').style.display = 'block';
    }

    // 가격 정보 테이블 생성 (매진 상태 처리 추가)
    res.priced.forEach(item => {
      const tr = document.createElement('tr');
      
      // 매진 상태에 따라 가격 표시 변경
      const priceDisplay = item.isSoldOut ? '매진' : item.price.toLocaleString();
      const priceClass = item.isSoldOut ? 'sold-out' : '';
      
      tr.innerHTML = `
        <td>${item.label}</td>
        <td class="${priceClass}">${priceDisplay}</td>
        <td><a href="${item.url}" target="_blank">열기</a></td>
        <td><button data-url="${item.url}">복사</button></td>`;
      $('#tbl tbody').appendChild(tr);
    });

    // 최저가 정보 표시 (예약 가능한 객실만)
    if (res.cheapest) {
      $('#cheapest').innerHTML = `
        <td>🏆 최저가</td>
        <td>${res.cheapest.price.toLocaleString()}</td>
        <td><a href="${res.cheapest.url}" target="_blank">열기</a></td>
        <td><button data-url="${res.cheapest.url}">복사</button></td>`;
    } else {
      // 모든 객실이 매진인 경우
      $('#cheapest').innerHTML = `
        <td colspan="4" class="no-available">모든 CID에서 예약 가능한 객실이 없습니다.</td>`;
    }

    $('#tbl').style.display = 'table';

    // 제휴 링크 표시
    res.affiliateLinks.forEach(link => {
      const li = document.createElement('li');
      li.innerHTML = `<a href="${link.url}" target="_blank">${link.label}</a>`;
      $('#affList').appendChild(li);
    });
    $('#aff').style.display = 'block';

    // 복사 버튼 이벤트 리스너 추가
    document.querySelectorAll('button[data-url]').forEach(b =>
      b.addEventListener('click', () => copy(b.dataset.url))
    );
  } catch (err) {
    console.error(err);
    alert('오류가 발생했습니다.');
  } finally {
    $('#loading').style.display = 'none';
  }
});
