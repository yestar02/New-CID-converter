import express from 'express';
import axios from 'axios';
import cheerio from 'cheerio';
import path from 'path';
import { fileURLToPath } from 'url';
import { dirname } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const app = express();
const port = process.env.PORT || 3000;

app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// CID 목록 (엑셀 없이 하드코딩)
const STATIC_CID_LIST = [
  { label: '구글 지도 1', cid: 1833982 },
  { label: '구글 지도 2', cid: 1917615 },
  { label: '구글 지도 3', cid: 1829668 },
  { label: '구글 검색 1', cid: 1908617 },
  { label: '구글 검색 2', cid: 1921868 },
  { label: '구글 검색 3', cid: 1922847 },
  { label: '네이버', cid: 1881505 },
  { label: 'Bing', cid: 1911217 },
  { label: '다음', cid: 1908762 },
  { label: 'DuckDuckGo', cid: 1895204 },
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
  { label: '대한항공', cid: 1904827 },
  { label: '아시아나항공', cid: 1806212 },
  { label: '에어서울', cid: 1800120 }
];

// 제휴 링크 (엑셀 없이 하드코딩)
const AFFILIATE_LINKS = [
  { label: '국민', url: 'https://www.agoda.com/ko-kr/kbcard' },
  { label: '우리', url: 'https://www.agoda.com/ko-kr/wooricard' },
  { label: '우리(마스터)', url: 'https://www.agoda.com/ko-kr/wooricardmaster' },
  { label: '비씨', url: 'https://www.agoda.com/ko-kr/bccard' },
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

// 1800000~1999999 범위에서 무작위 50개 샘플링
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

// URL 유효성 검사
function validateAgodaUrl(url) {
  return (
    url &&
    /agoda\.com/.test(url) &&
    !/\/search/.test(url) &&
    /cid=\d+/.test(url)
  );
}

// CID 교체
function replaceCid(url, newCid) {
  if (url.includes('cid=-1')) {
    return url.replace('cid=-1', `cid=${newCid}`);
  }
  return url.replace(/cid=\d+/, `cid=${newCid}`);
}

// 호텔명 및 가격 추출
async function fetchHotelInfo(url) {
  const headers = {
    'Accept-Language': 'ko,ko-KR;q=0.9,en-US;q=0.8,en;q=0.7',
    'ag-language-locale': 'ko-kr'
  };

  try {
    const html = await axios.get(url, { headers }).then(r => r.data);
    const $ = cheerio.load(html);
    const scriptTag = $('script[data-selenium="script-initparam"]');
    const apiUrlMatch = scriptTag.text().match(/apiUrl\s*=\s*"(.+?)"/);

    if (!apiUrlMatch) return { hotel: '호텔 정보 없음', price: null };

    const apiUrl = `https://www.agoda.com${apiUrlMatch[1].replace(/&amp;/g, '&')}`;
    const apiResponse = await axios.get(apiUrl, { headers });
    const apiData = apiResponse.data;
    const hotel = apiData?.hotelInfo?.name || '호텔 이름 없음';
    const price = apiData?.rooms?.[0]?.directPrice?.originalPrice || null;

    return { hotel, price };
  } catch (err) {
    console.error(err);
    return { hotel: '호텔 정보 조회 실패', price: null };
  }
}

// 변환 및 최저가 탐색
app.post('/convert', async (req, res) => {
  const { url } = req.body;
  if (!validateAgodaUrl(url)) {
    return res.status(400).json({ message: '잘못된 아고다 URL입니다.' });
  }

  try {
    // CID 교체 링크 생성
    const links = CID_LIST.map(o => ({
      label: o.label,
      cid: o.cid,
      url: replaceCid(url, o.cid)
    }));

    // 비동기로 가격 수집 (최대 75개 동시 요청, Render 무료 플랜에 적합)
    const results = await Promise.allSettled(
      links.map(async link => {
        const { hotel, price } = await fetchHotelInfo(link.url);
        return { ...link, hotel, price };
      })
    );

    // 성공한 결과만 추출
    const priced = results
      .filter(r => r.status === 'fulfilled' && r.value.price != null)
      .map(r => r.value);

    // 최저가 계산
    priced.sort((a, b) => a.price - b.price);
    const cheapest = priced[0] || null;

    // 호텔명은 첫 성공 응답에서 확보
    const hotel = priced[0]?.hotel || '호텔 정보 없음';

    // 결과 반환
    res.json({
      hotel,
      priced,
      cheapest,
      affiliateLinks: AFFILIATE_LINKS
    });
  } catch (err) {
    console.error(err);
    res.status(500).json({ message: '서버 오류' });
  }
});

// 메인 페이지
app.get('/', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.listen(port, () => {
  console.log(`Agoda CID Converter server listening on port ${port}`);
});
