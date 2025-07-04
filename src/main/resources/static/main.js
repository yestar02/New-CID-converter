// src/main/resources/static/main.js

// 개발자도구 차단
if (typeof DisableDevtool !== 'undefined') {
  DisableDevtool({ disableMenu: true, clearLog: true });
}

const $ = selector => document.querySelector(selector);
const $$ = selector => document.querySelectorAll(selector);
const copy = text => navigator.clipboard.writeText(text).then(() => alert('복사되었습니다!'));

// DOM 요소들
const elements = {
  form: $('#frm'),
  urlInput: $('#agodaUrl'),
  convertBtn: $('#convertBtn'),
  helpBtn: $('#helpBtn'),
  resetBtn: $('#resetBtn'),
  loading: $('#loading'),
  mainTitle: $('#mainTitle'),
  hotelTitle: $('#hotelTitle'),
  tablesContainer: $('#tablesContainer'),
  table: $('#tbl'),
  tableBody: $('#tbl tbody'),
  cheapest: $('#cheapest'),
  affList: $('#affList'),
  helpPopup: $('#helpPopup'),
  closePopup: $('#closePopup')
};

// 초기 상태로 리셋
function resetToInitial() {
  elements.helpBtn.style.display = 'inline-block';
  elements.resetBtn.style.display = 'none';
  elements.hotelTitle.style.display = 'none';
  elements.tablesContainer.style.display = 'none';
  elements.urlInput.value = '';
  elements.tableBody.innerHTML = '';
  elements.cheapest.innerHTML = '';
  elements.affList.innerHTML = '';
}

// 결과 화면으로 전환
function showResults() {
  elements.helpBtn.style.display = 'none';
  elements.resetBtn.style.display = 'inline-block';
  elements.hotelTitle.style.display = 'block';
  elements.tablesContainer.style.display = 'flex';
}

// 폼 제출 이벤트
elements.form.addEventListener('submit', async e => {
  e.preventDefault();
  const url = elements.urlInput.value.trim();
  if (!url) return alert('URL을 입력하세요.');

  elements.loading.style.display = 'block';
  elements.tablesContainer.style.display = 'none';
  elements.hotelTitle.style.display = 'none';

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

    // 호텔명과 가격 표시
    const hotelName = res.hotel;
    const initialPriceValue = res.initialPrice;
    const priceText = initialPriceValue > 0
      ? initialPriceValue.toLocaleString() + '원'
      : '가격 정보 없음';
    
    elements.hotelTitle.textContent = `${hotelName} - ${priceText}`;

    // CID별 가격 테이블 생성
    elements.tableBody.innerHTML = '';
    elements.cheapest.innerHTML = '';

    res.priced.forEach(item => {
      const tr = document.createElement('tr');
      const priceDisplay = item.soldOut ? '매진' : item.price.toLocaleString();
      const priceClass = item.soldOut ? 'sold-out' : '';
      
      tr.innerHTML = `
        <td>${item.label}</td>
        <td class="${priceClass}">${priceDisplay}</td>
        <td><button class="btn-link" onclick="window.open('${item.url}', '_blank')">열기</button></td>
        <td><button class="btn-link btn-copy" onclick="copyUrl('${item.url}')">복사</button></td>
      `;
      elements.tableBody.appendChild(tr);
    });

    // 최저가 표시
    const available = res.priced.filter(i => !i.soldOut && i.price > 0);
    if (available.length) {
      const best = available.reduce((min, current) => 
        current.price < min.price ? current : min
      );
      elements.cheapest.innerHTML = `
        <td>🏆 최저가</td>
        <td>${best.price.toLocaleString()}</td>
        <td><button class="btn-link" onclick="window.open('${best.url}', '_blank')">열기</button></td>
        <td><button class="btn-link btn-copy" onclick="copyUrl('${best.url}')">복사</button></td>
      `;
    } else {
      elements.cheapest.innerHTML = `
        <td colspan="4" class="no-available">모든 객실이 매진입니다.</td>
      `;
    }

    // 제휴 링크 테이블 생성
    elements.affList.innerHTML = '';
    res.affiliateLinks.forEach(link => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${link.label}</td>
        <td><button class="btn-link" onclick="window.open('${link.url}', '_blank')">${link.label} 바로가기</button></td>
      `;
      elements.affList.appendChild(tr);
    });

    showResults();

  } catch (err) {
    console.error(err);
    alert('오류가 발생했습니다.');
  } finally {
    elements.loading.style.display = 'none';
  }
});

// URL 복사 함수
function copyUrl(url) {
  copy(url);
}

// 리셋 버튼 이벤트
elements.resetBtn.addEventListener('click', resetToInitial);

// 도움말 팝업 이벤트
elements.helpBtn.addEventListener('click', () => {
  elements.helpPopup.style.display = 'flex';
});

elements.closePopup.addEventListener('click', () => {
  elements.helpPopup.style.display = 'none';
});

// 팝업 외부 클릭 시 닫기
elements.helpPopup.addEventListener('click', (e) => {
  if (e.target === elements.helpPopup) {
    elements.helpPopup.style.display = 'none';
  }
});

// 초기 상태로 시작
resetToInitial();
