import express from 'express';
import axios from 'axios';
import cheerio from 'cheerio';
import path from 'path';
import { fileURLToPath } from 'url';
import { dirname } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const app = express();
const PORT = process.env.PORT || 3000;
const HOST = '0.0.0.0'; // Render 배포를 위한 필수 설정

app.use(express.json({ limit: '10mb' }));
app.use(express.static(path.join(__dirname, 'public')));

// 정적 CID 목록 (엑셀 파일 제거)
const STATIC_CID_LIST = [
  // 검색엔진 CID (구글지도 실제 값으로 업데이트)
  { label: '구글 지도 1', cid: 1833982 },
  { label: '구글 지도 2', cid: 1917614 },
  { label: '구글 지도 3', cid: 1829668 },
  { label: '구글 검색 1', cid: 1908617 },
  { label: '구글 검색 2', cid: 1921868 },
  { label: '구글 검색 3', cid: 1922847 },
  { label: '네이버', cid: 1881505 },
  { label: 'Bing', cid: 1911217 },
  { label: '다음', cid: 1908762 },
  { label: 'DuckDuckGo', cid: 1895204 },
  
  // 카드사 CID
  { label: '국민', cid: 1563295 },
  { label: '우리', cid: 1654104 },
  { label: '우리(마스터)', cid: 1932810 },
  { label: 'BC', cid: 1748498 },
  { label: '신한', cid: 1760133 },
  { label: '신한(마스터)', cid: 1917257 },
  { label: '토스', cid: 1917334 },
  { label: '하나', cid: 1729471 },
  { label: '카카오페이', cid: 1845109 },
  { label: '마스터카드', cid: 1889572 },
  { label: '유니온페이', cid: 1801110 },
  { label: '비자', cid: 1889319 },
  
  // 항공사 CID
  { label: '대한항공', cid: 1904827 },
  { label: '아시아나항공', cid: 1806212 },
  { label: '에어서울', cid: 1800120 }
];

// 제휴 링크 목록
const AFFILIATE_LINKS = [
  { label: '국민', url: 'https://www.agoda.com/ko-kr/kbcard' },
  { label: '우리', url: 'https://www.agoda.com/ko-kr/wooricard' },
  { label: '우리(마스터)', url: 'https://www.agoda.com/ko-kr/wooricardmaster' },
  { label: 'BC', url: 'https://www.agoda.com/ko-kr/bccard' },
  { label: '신한', url: 'https://www.agoda.com/ko-kr/shinhancard' },
  { label: '신한(마스터)', url: 'https://www.agoda.com/ko-kr/shinhanmaster' },
  { label: '토스', url: 'https://www.agoda.com/ko-kr/tossbank' },
  { label: '하나', url: 'https://www.agoda.com/ko-kr/hanacard' },
  { label: '카카오페이', url: 'https://www.agoda.com/ko-kr/kakaopay' },
  { label: '마스터카드', url: 'https://www.agoda.com/ko-kr/krmastercard' },
  { label: '유니온페이', url: 'https://www.agoda.com/ko-kr/unionpayKR' },
  { label: '비자', url: 'https://www.agoda.com/ko-kr/visakorea' },
  { label: '대한항공', url: 'https://www.agoda.com/ko-kr/koreanair' },
  { label: '아시아나항공', url: 'https://www.agoda.com/ko-kr/flyasiana' },
  { label: '에어서울', url: 'https://www.agoda.com/ko-kr/airseoul' }
];

// 1,800,000~1,999,999 범위에서 무작위 50개 샘플링
function getRandomCids(count, min, max) {
  const cids = new Set();
  while (cids.size < count) {
    const cid = Math.floor(Math.random() * (max - min + 1)) + min;
    cids.add(cid);
  }
  return Array.from(cids).map(cid => ({
    label: `AUTO-${cid}`,
    cid
  }));
}

const RANDOM_CIDS = getRandomCids(50, 1800000, 1999999);
const CID_LIST = [...STATIC_CID_LIST, ...RANDOM_CIDS];

// URL 유효성 검사 함수
function validateAgodaUrl(url) {
  if (!url || typeof url !== 'string') return false;
  if (!/agoda\.com/.test(url)) return false;
  if (/\/search/.test(url)) return false;
  if (!/cid=[\d-]+/.test(url)) return false;
  return true;
}

// CID 교체 함수
function replaceCid(url, newCid) {
  try {
    if (url.includes('cid=-1')) {
      return url.replace('cid=-1', `cid=${newCid}`);
    }
    return url.replace(/cid=\d+/, `cid=${newCid}`);
  } catch (error) {
    console.error('CID 교체 오류:', error);
    return url;
  }
}

// 호텔 정보 및 가격 추출 (향상된 에러 처리)
async function fetchHotelInfo(url) {
  const headers = {
    'Accept-Language': 'ko,ko-KR;q=0.9,en-US;q=0.8,en;q=0.7',
    'ag-language-locale': 'ko-kr',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36'
  };

  try {
    // 요청 타임아웃 설정 (Render 무료 플랜 최적화)
    const htmlResponse = await axios.get(url, { 
      headers, 
      timeout: 10000,
      maxRedirects: 5
    });
    
    const $ = cheerio.load(htmlResponse.data);
    const scriptTag = $('script[data-selenium="script-initparam"]');
    
    if (!scriptTag.length) {
      return { hotel: '스크립트 태그 없음', price: null };
    }

    const scriptContent = scriptTag.text();
    const apiUrlMatch = scriptContent.match(/apiUrl\s*=\s*"(.+?)"/);

    if (!apiUrlMatch) {
      return { hotel: 'API URL 없음', price: null };
    }

    const apiUrl = `https://www.agoda.com${apiUrlMatch[1].replace(/&amp;/g, '&')}`;
    
    const apiResponse = await axios.get(apiUrl, { 
      headers, 
      timeout: 8000 
    });
    
    const apiData = apiResponse.data;
    const hotel = apiData?.hotelInfo?.name || '호텔명 추출 실패';
    const price = apiData?.rooms?.[0]?.directPrice?.originalPrice || null;

    return { hotel, price };
  } catch (error) {
    console.error(`호텔 정보 조회 실패 (${url}):`, error.message);
    return { hotel: '조회 실패', price: null };
  }
}

// 배치 처리 함수 (Render 메모리 최적화)
async function processBatch(links, batchSize = 10) {
  const results = [];
  
  for (let i = 0; i < links.length; i += batchSize) {
    const batch = links.slice(i, i + batchSize);
    
    const batchResults = await Promise.allSettled(
      batch.map(async link => {
        const { hotel, price } = await fetchHotelInfo(link.url);
        return { ...link, hotel, price };
      })
    );

    results.push(...batchResults);
    
    // 배치 간 짧은 지연 (서버 부하 감소)
    if (i + batchSize < links.length) {
      await new Promise(resolve => setTimeout(resolve, 500));
    }
  }
  
  return results;
}

// 메인 변환 API
app.post('/convert', async (req, res) => {
  const { url } = req.body;

  if (!validateAgodaUrl(url)) {
    return res.status(400).json({ 
      success: false, 
      message: '유효하지 않은 아고다 URL입니다.' 
    });
  }

  try {
    // CID 교체 링크 생성
    const links = CID_LIST.map(item => ({
      label: item.label,
      cid: item.cid,
      url: replaceCid(url, item.cid)
    }));

    console.log(`처리 시작: ${links.length}개 CID 검색`);

    // 배치 처리로 메모리 최적화
    const results = await processBatch(links, 8); // Render 무료 플랜 최적화

    // 성공한 결과만 추출
    const priced = results
      .filter(r => r.status === 'fulfilled' && r.value.price != null && r.value.price > 0)
      .map(r => r.value)
      .sort((a, b) => a.price - b.price);

    const cheapest = priced[0] || null;
    const hotel = priced[0]?.hotel || '호텔 정보 없음';

    console.log(`처리 완료: ${priced.length}개 가격 발견`);

    res.json({
      success: true,
      hotel,
      priced,
      cheapest,
      affiliateLinks: AFFILIATE_LINKS,
      totalChecked: links.length,
      foundPrices: priced.length
    });

  } catch (error) {
    console.error('변환 처리 중 오류:', error);
    res.status(500).json({ 
      success: false, 
      message: '서버 내부 오류가 발생했습니다.' 
    });
  }
});

// 헬스 체크 엔드포인트
app.get('/health', (req, res) => {
  res.json({ 
    status: 'OK', 
    timestamp: new Date().toISOString(),
    environment: process.env.NODE_ENV || 'development'
  });
});

// 메인 페이지
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// 404 핸들러
app.use('*', (req, res) => {
  res.status(404).json({ message: 'Page not found' });
});

// 에러 핸들러
app.use((err, req, res, next) => {
  console.error('서버 오류:', err);
  res.status(500).json({ message: 'Internal server error' });
});

// 서버 시작 (Render 호환성)
app.listen(PORT, HOST, () => {
  console.log(`🚀 Agoda CID Converter server running on http://${HOST}:${PORT}`);
  console.log(`📍 Environment: ${process.env.NODE_ENV || 'development'}`);
  console.log(`🔧 Total CIDs: ${CID_LIST.length}`);
});
