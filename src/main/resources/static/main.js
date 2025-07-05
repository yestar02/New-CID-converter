// src/main/resources/static/main.js

// 개발자도구 차단
if (typeof DisableDevtool !== 'undefined') {
    DisableDevtool({ disableMenu: true, clearLog: true });
}

const $ = selector => document.querySelector(selector);
const $$ = selector => document.querySelectorAll(selector);
const copy = text => navigator.clipboard.writeText(text).then(() => {
    // 복사 성공 피드백
});

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
    cheapestContainer: $('#cheapestContainer'),
    cheapestBody: $('#cheapestBody'),
    affList: $('#affList'),
    helpPopup: $('#helpPopup'),
    closePopup: $('#closePopup'),
    progressFill: $('#progressFill'),
    progressPercent: $('#progressPercent')
};

let currentEventSource = null;

// 세션 ID 생성
function generateSessionId() {
    return Date.now().toString(36) + Math.random().toString(36).substr(2);
}

// 사용자의 현재 쿠키 추출
function getUserCookies() {
    const cookies = {};
    document.cookie.split(';').forEach(cookie => {
        const [name, value] = cookie.trim().split('=');
        if (name && value) {
            cookies[name] = decodeURIComponent(value);
        }
    });
    console.log('추출된 사용자 쿠키:', Object.keys(cookies).length + '개');
    return cookies;
}

// 진행율 업데이트
function updateProgress(percentage) {
    elements.progressFill.style.width = percentage + '%';
    elements.progressPercent.textContent = percentage + '%';
}

// 초기 상태로 리셋
function resetToInitial() {
    if (currentEventSource) {
        currentEventSource.close();
        currentEventSource = null;
    }
    
    // body 클래스 제거하여 초기 중앙 정렬로 복귀
    document.body.classList.remove('has-results');
    
    elements.helpBtn.style.display = 'inline-block';
    elements.resetBtn.style.display = 'none';
    elements.hotelTitle.style.display = 'none';
    elements.tablesContainer.style.display = 'none';
    elements.cheapestContainer.style.display = 'none';
    elements.urlInput.value = '';
    elements.tableBody.innerHTML = '';
    elements.cheapest.innerHTML = '';
    elements.cheapestBody.innerHTML = '';
    elements.affList.innerHTML = '';
    updateProgress(0);
}

// 결과 화면으로 전환
function showResults() {
    // body 클래스 추가하여 상단 정렬로 변경
    document.body.classList.add('has-results');
    
    elements.helpBtn.style.display = 'none';
    elements.resetBtn.style.display = 'inline-block';
    elements.hotelTitle.style.display = 'block';
    elements.tablesContainer.style.display = 'flex';
}

// 가격에 따른 배경색 결정
function getPriceBackgroundColor(price, prices) {
    if (prices.length === 0) return '';
    
    const sortedPrices = [...prices].sort((a, b) => a - b);
    const index = sortedPrices.indexOf(price);
    const percentile = index / (sortedPrices.length - 1);
    
    if (percentile <= 0.33) return 'price-green';  // 상위 33%
    else if (percentile <= 0.66) return 'price-yellow'; // 33~66%
    else return 'price-red'; // 나머지
}

// 폼 제출 이벤트
elements.form.addEventListener('submit', async e => {
    e.preventDefault();
    const url = elements.urlInput.value.trim();
    if (!url) return alert('URL을 입력하세요.');

    const sessionId = generateSessionId();
    const userCookies = getUserCookies(); // 사용자 쿠키 추출
    
    elements.loading.style.display = 'block';
    elements.tablesContainer.style.display = 'none';
    elements.hotelTitle.style.display = 'none';
    elements.cheapestContainer.style.display = 'none';
    updateProgress(0);

    // SSE 연결 설정
    currentEventSource = new EventSource(`/api/progress/${sessionId}`);
    
    currentEventSource.onmessage = function(event) {
        const data = JSON.parse(event.data);
        
        if (data.type === 'progress') {
            updateProgress(data.percentage);
        } else if (data.type === 'complete') {
            handleCompletionData(data.result);
            currentEventSource.close();
            currentEventSource = null;
        } else if (data.type === 'error') {
            alert(data.message);
            elements.loading.style.display = 'none';
            currentEventSource.close();
            currentEventSource = null;
        }
    };

    currentEventSource.onerror = function() {
        alert('서버 연결에 오류가 발생했습니다.');
        elements.loading.style.display = 'none';
        if (currentEventSource) {
            currentEventSource.close();
            currentEventSource = null;
        }
    };

    try {
        // 백엔드에 처리 요청 (사용자 쿠키 포함)
        const response = await fetch('/api/convert', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ 
                url, 
                sessionId,
                userCookies // 사용자 쿠키 추가
            })
        });

        const result = await response.json();
        if (!result.success) {
            alert(result.message);
            elements.loading.style.display = 'none';
            if (currentEventSource) {
                currentEventSource.close();
                currentEventSource = null;
            }
        }
        
    } catch (err) {
        console.error(err);
        alert('요청 처리 중 오류가 발생했습니다.');
        elements.loading.style.display = 'none';
        if (currentEventSource) {
            currentEventSource.close();
            currentEventSource = null;
        }
    }
});

// 완료 데이터 처리
function handleCompletionData(res) {
    console.log('처리 완료:', res);
    
    // 호텔명과 가격 표시
    const hotelName = res.hotel;
    const initialPriceValue = res.initialPrice;
    const priceText = initialPriceValue > 0 
        ? '₩' + initialPriceValue.toLocaleString()
        : '가격 정보 없음';
    elements.hotelTitle.textContent = `${hotelName} - ${priceText}`;

    // 가격 배경색을 위한 유효한 가격 배열 생성
    const validPrices = res.priced
        .filter(item => !item.soldOut && item.price > 0)
        .map(item => item.price);

    // 최저가 표 생성
    const availableItems = res.priced.filter(item => !item.soldOut && item.price > 0);
    if (availableItems.length > 0) {
        const cheapestItem = availableItems.reduce((min, current) => 
            current.price < min.price ? current : min
        );
        
        elements.cheapestContainer.style.display = 'block';
        elements.cheapestBody.innerHTML = `
            <tr>
                <td>🏆 ${cheapestItem.label}</td>
                <td>₩${cheapestItem.price.toLocaleString()}</td>
                <td><button class="btn-link" onclick="window.open('${cheapestItem.url}', '_blank')">열기</button></td>
                <td><button class="btn-link btn-copy" onclick="copyUrl('${cheapestItem.url}', this)">복사</button></td>
            </tr>
        `;
    }

    // CID별 가격 테이블 생성
    elements.tableBody.innerHTML = '';
    res.priced.forEach(item => {
        const tr = document.createElement('tr');
        const priceDisplay = item.soldOut ? '매진' : '₩' + item.price.toLocaleString();
        const priceClass = item.soldOut ? 'sold-out' : '';
        
        // 가격 배경색 적용
        let bgColorClass = '';
        if (!item.soldOut && item.price > 0) {
            bgColorClass = getPriceBackgroundColor(item.price, validPrices);
        }
        
        tr.innerHTML = `
            <td>${item.label}</td>
            <td class="${priceClass} ${bgColorClass}">${priceDisplay}</td>
            <td><button class="btn-link" onclick="window.open('${item.url}', '_blank')">열기</button></td>
            <td><button class="btn-link btn-copy" onclick="copyUrl('${item.url}', this)">복사</button></td>
        `;
        elements.tableBody.appendChild(tr);
    });

    // 제휴 링크 생성
    elements.affList.innerHTML = '';
    res.affiliateLinks.forEach(link => {
        const li = document.createElement('li');
        li.innerHTML = `<button class="affiliate-btn" onclick="window.open('${link.url}', '_blank')">${link.label}</button>`;
        elements.affList.appendChild(li);
    });

    showResults();
    elements.loading.style.display = 'none';
}

// URL 복사 함수
function copyUrl(url, button) {
    copy(url).then(() => {
        const originalText = button.textContent;
        button.textContent = '복사됨!';
        setTimeout(() => {
            button.textContent = originalText;
        }, 1000);
    }).catch(err => {
        console.error('복사 실패:', err);
        alert('복사에 실패했습니다.');
    });
}

// 리셋 버튼 이벤트
elements.resetBtn.addEventListener('click', resetToInitial);

// 도움말 버튼 이벤트
elements.helpBtn.addEventListener('click', () => {
    elements.helpPopup.style.display = 'flex';
});

// 팝업 닫기 이벤트
elements.closePopup.addEventListener('click', () => {
    elements.helpPopup.style.display = 'none';
});

// 팝업 외부 클릭시 닫기
elements.helpPopup.addEventListener('click', (e) => {
    if (e.target === elements.helpPopup) {
        elements.helpPopup.style.display = 'none';
    }
});

// 페이지 언로드 시 SSE 연결 정리
window.addEventListener('beforeunload', () => {
    if (currentEventSource) {
        currentEventSource.close();
    }
});

// 키보드 이벤트 (Enter로 폼 제출)
elements.urlInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') {
        elements.form.dispatchEvent(new Event('submit'));
    }
});

// Escape 키로 팝업 닫기
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && elements.helpPopup.style.display === 'flex') {
        elements.helpPopup.style.display = 'none';
    }
});

// 초기 상태 설정
resetToInitial();

// 페이지 로드 완료 시 URL 입력창에 포커스
window.addEventListener('load', () => {
    elements.urlInput.focus();
});
