import express from 'express';
import axios from 'axios';
import * as cheerio from 'cheerio';
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
  { label: '현대', cid: 1768446 },
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
  { label: '현대', url: 'https://www.agoda.com/ko-kr/hyundaicard' },
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
// 개발자도구 차단
function getRandomCids(count, min, max) {
  const set = new Set();
  while (set.size < count) {
    set.add(Math.floor(Math.random() * (max - min + 1)) + min);
  }
  return [...set].map(cid => ({ label: `AUTO-${cid}`, cid }));
}
const CID_LIST = [...STATIC_CID_LIST, ...getRandomCids(50, 1800000, 1999999)];

// URL 검증
function validateUrl(url) {
  return url && /agoda\.com/.test(url) && !/\/search/.test(url) && /cid=\d+/.test(url);
}
// CID 교체
function replaceCid(url, cid) {
  return url.includes('cid=-1') 
    ? url.replace('cid=-1', `cid=${cid}`) 
    : url.replace(/cid=\d+/, `cid=${cid}`);
}
// 호텔명·가격 조회
async function fetchHotelInfo(url) {
  const headers = {
    'Accept-Language': 'ko,ko-KR;q=0.9',
    'ag-language-locale': 'ko-kr',
    'User-Agent': 'Mozilla/5.0'
  };
  try {
    const html = await axios.get(url, { headers, timeout: 10000 }).then(r => r.data);
    const $ = cheerio.load(html);
    const init = $('script[data-selenium="script-initparam"]').text();
    const apiPath = init.match(/apiUrl\s*=\s*"(.+?)"/)?.[1]?.replace(/&amp;/g, '&');
    if (!apiPath) return { hotel: null, price: null };
    const data = await axios.get(`https://www.agoda.com${apiPath}`, { headers, timeout: 8000 }).then(r => r.data);
    return {
      hotel: data.hotelInfo?.name || null,
      price: data.rooms?.[0]?.directPrice?.originalPrice || null
    };
  } catch {
    return { hotel: null, price: null };
  }
}

// 변환 API
app.post('/convert', async (req, res) => {
  const { url } = req.body;
  if (!validateUrl(url)) return res.status(400).json({ message: '유효하지 않은 URL' });

  // 링크 생성 및 배치 처리
  const links = CID_LIST.map(o => ({ ...o, url: replaceCid(url, o.cid) }));
  const results = [];
  for (let i = 0; i < links.length; i += 8) {
    const batch = links.slice(i, i + 8);
    const ps = batch.map(l => fetchHotelInfo(l.url).then(info => ({ ...l, ...info })));
    results.push(...await Promise.all(ps));
    await new Promise(r => setTimeout(r, 500));
  }

  const priced = results.filter(r => r.price != null).sort((a, b) => a.price - b.price);
  const cheapest = priced[0] || null;
  const hotel = cheapest?.hotel || null;

  res.json({ hotel, priced, cheapest, affiliateLinks: AFFILIATE_LINKS });
});

// 정적 페이지
app.get('/', (_, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));

app.listen(PORT, HOST, () => console.log(`Listening on ${HOST}:${PORT}`));