// 개발자 도구 차단 (프로덕션에서만 활성화)
if (typeof DisableDevtool !== 'undefined') {
  DisableDevtool({
    disableMenu: true,
    disableSelect: true,
    disableCopy: true,
    disableCut: true,
    disablePaste: true,
    clearLog: true,
    ondevtoolopen: (type) => {
      console.warn('개발자 도구가 감지되었습니다.');
    }
  });
}

// DOM 헬퍼 함수
const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => document.querySelectorAll(selector);

// 클립보드 복사 함수
async function copyToClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    showToast('링크가 클립보드에 복사되었습니다!');
  } catch (err) {
    // 폴백 방법
    const textArea = document.createElement('textarea');
    textArea.value = text;
    document.body.appendChild(textArea);
    textArea.select();
    document.execCommand('copy');
    document.body.removeChild(textArea);
    showToast('링크가 복사되었습니다!');
  }
}

// 토스트 메시지 표시
function showToast(message) {
  const toast = document.createElement('div');
  toast.style.cssText = `
    position: fixed;
    top: 20px;
    right: 20px;
    background: #2ecc71;
    color: white;
    padding: 12px 20px;
    border-radius: 6px;
    z-index: 1000;
    animation: slideIn 0.3s ease;
  `;
  toast.textContent = message;
  document.body.appendChild(toast);
  
  setTimeout(() => {
    toast.style.animation = 'slideOut 0.3s ease';
    setTimeout(() => document.body.removeChild(toast), 300);
  }, 2000);
}

// CSS 애니메이션 추가
const style = document.createElement('style');
style.textContent = `
  @keyframes slideIn {
    from { transform: translateX(100%); opacity: 0; }
    to { transform: translateX(0); opacity: 1; }
  }
  @keyframes slideOut {
    from { transform: translateX(0); opacity: 1; }
    to { transform: translateX(100%); opacity: 0; }
  }
`;
document.head.appendChild(style);

// 폼 제출 처리
$('#frm').addEventListener('submit', async (e) => {
  e.preventDefault();
  
  const url = $('#agodaUrl').value.trim();
  if (!url) {
    alert('아고다 URL을 입력해주세요.');
    return;
  }

  // UI 초기화
  $('#hotelTitle').style.display = 'none';
  $('#tbl').style.display = 'none';
  $('#affiliateLinks').style.display = 'none';
  $('#stats').style.display = 'none';
  $('#loading').style.display = 'block';
  $('#submitBtn').disabled = true;
  $('#submitBtn').textContent = '처리 중...';
  
  // 테이블 내용 초기화
  $('#tbl tbody').innerHTML = '';
  $('#cheapest').innerHTML = '';
  $('#affiliateBody').innerHTML = '';

  try {
    const response = await fetch('/convert', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ url })
    });

    const data = await response.json();

    if (!data.success) {
      throw new Error(data.message || '변환에 실패했습니다.');
    }

    // 호텔명 표시
    $('#hotelTitle').textContent = `${data.hotel} - 가격 비교 결과`;
    $('#hotelTitle').style.display = 'block';

    // 가격 정보 테이블 생성
    if (data.priced && data.priced.length > 0) {
      data.priced.forEach((item, index) => {
        const row = document.createElement('tr');
        row.innerHTML = `
          <td>${item.label}</td>
          <td>${item.price ? item.price.toLocaleString() : 'N/A'}</td>
          <td><a href="${item.url}" target="_blank" class="button">열기</a></td>
          <td><button type="button" data-url="${item.url}">복사</button></td>
        `;
        $('#tbl tbody').appendChild(row);
      });

      // 최저가 정보 표시
      if (data.cheapest) {
        $('#cheapest').innerHTML = `
          <td>🏆 최저가</td>
          <td>${data.cheapest.price.toLocaleString()}</td>
          <td><a href="${data.cheapest.url}" target="_blank" class="button">열기</a></td>
          <td><button type="button" data-url="${data.cheapest.url}">복사</button></td>
        `;
      }

      $('#tbl').style.display = 'table';
    }

    // 제휴 링크 표시
    if (data.affiliateLinks && data.affiliateLinks.length > 0) {
      data.affiliateLinks.forEach(link => {
        const row = document.createElement('tr');
        row.innerHTML = `
          <td>${link.label}</td>
          <td><a href="${link.url}" target="_blank" class="button">바로가기</a></td>
        `;
        $('#affiliateBody').appendChild(row);
      });
      $('#affiliateLinks').style.display = 'block';
    }

    // 통계 정보 표시
    $('#statsText').textContent = `총 ${data.totalChecked}개 CID 중 ${data.foundPrices}개 가격 발견`;
    $('#stats').style.display = 'block';

    // 복사 버튼 이벤트 리스너 추가
    $$('button[data-url]').forEach(btn => {
      btn.addEventListener('click', () => {
        copyToClipboard(btn.dataset.url);
      });
    });

  } catch (error) {
    console.error('변환 오류:', error);
    alert(`오류가 발생했습니다: ${error.message}`);
  } finally {
    // UI 상태 복원
    $('#loading').style.display = 'none';
    $('#submitBtn').disabled = false;
    $('#submitBtn').textContent = '변환';
  }
});

// 페이지 로드 시 초기화
document.addEventListener('DOMContentLoaded', () => {
  console.log('🚀 아고다 CID 변환기가 준비되었습니다.');
  
  // URL에 포커스
  $('#agodaUrl').focus();
  
  // 엔터키로 제출 가능하도록
  $('#agodaUrl').addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
      $('#frm').dispatchEvent(new Event('submit'));
    }
  });
});
