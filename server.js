/* ----------------------- 환경 설정 ----------------------- */
import express from 'express';
import axios   from 'axios';
import cheerio from 'cheerio';
import path    from 'path';
import { fileURLToPath } from 'url';

const app  = express();
const port = process.env.PORT || 3000;
app.use(express.json());
app.use(express.static('public'));

/* ----------------------- CID 목록 ----------------------- */
const CID_LIST = [
  /* 검색엔진 */
  { label: '구글 지도 1', cid: 1833982 },
  { label: '구글 지도 2', cid: 1917615 },
  { label: '구글 지도 3', cid: 1829668 },
  { label: '구글 검색 1', cid: 1908617 },
  { label: '구글 검색 2', cid: 1921868 },
  { label: '구글 검색 3', cid: 1922847 },
  { label: '네이버',       cid: 1881505 },
  { label: 'Bing',        cid: 1911217 },
  { label: '다음',        cid: 1908762 },
  { label: 'DuckDuckGo',  cid: 1895204 },

  /* 카드사 */
  { label: '국민', cid: 1563295 },
  { label: '우리', cid: 1654104 },
  { label: '우리(마스터)', cid: 1932810 },
  { label: 'BC',  cid: 1748498 },
  { label: '신한',        cid: 1760133 },
  { label: '신한(마스터)', cid: 1917257 },
  { label: '토스', cid: 1917334 },
  { label: '하나', cid: 1729471 },
  { label: '카카오페이',   cid: 1845109 },
  { label: '마스터카드',   cid: 1889572 },
  { label: '유니온페이',   cid: 1801110 },
  { label: '비자',        cid: 1889319 },

  /* 항공사 */
  { label: '대한항공',    cid: 1904827 },
  { label: '아시아나항공', cid: 1806212 },
  { label: '에어서울',    cid: 1800120 }
];

/* ----------------------- 유틸 함수 ----------------------- */
// URL 유효성 검사
function validateAgodaUrl(url) {
  return (
    url &&
    /agoda\.com/.test(url) &&
    !/\/search/.test(url) &&
    /cid=\d+/.test(url)
  );
}

// 원본 URL에 새 CID 적용
function replaceCid(url, newCid) {
  if (url.includes('cid=-1')) {
    return url.replace('cid=-1', `cid=${newCid}`);
  }
  return url.replace(/cid=\d+/, `cid=${newCid}`);
}

/* 호텔명 + 가격 추출 */
async function fetchHotelInfo(url) {
  const headers = {
    'Accept-Language': 'ko,ko-KR;q=0.9,en-US;q=0.8,en;q=0.7',
    'ag-language-locale': 'ko-kr'
  };

  // 1단계: HTML 요청 → 내부 API URL 추출
  const html = await axios.get(url, { headers }).then(r => r.data);
  const $    = cheerio.load(html);
  const init = $('script[data-selenium="script-initparam"]').text();
  const api  = /apiUrl\s*=\s*"(.+?)"/.exec(init)?.[1]
                 ?.replace(/&amp;/g, '&');

  // 2단계: JSON 요청으로 호텔명·가격 확보
  const apiUrl   = `https://www.agoda.com${api}`;
  const apiJson  = await axios.get(apiUrl, { headers }).then(r => r.data);
  const hotel    = apiJson?.hotelInfo?.name || '이름 없음';
  const roomRate = apiJson?.rooms[0]?.directPrice?.originalPrice || null; // 첫 객실가

  return { hotel, roomRate };
}

/* ----------------------- API 라우트 ----------------------- */
/* 변환 + 최저가 탐색 */
app.post('/convert', async (req, res) => {
  const { url } = req.body;
  if (!validateAgodaUrl(url)) {
    return res.status(400).json({ message: '잘못된 아고다 URL입니다.' });
  }

  try {
    /* ① CID 교체 링크 생성 */
    const links = CID_LIST.map(o => ({
      label: o.label,
      cid:   o.cid,
      url:   replaceCid(url, o.cid)
    }));

    /* ② 순차 CID 증분 검사 (cid=1~9999999) → 최저가 */
    const dynamicCids = [];
    for (let i = 1; i <= 1000000; i++) {          // 최대 50회
      const trialCid   = 1000000 + i;        // 예: 1000001, …
      const trialUrl   = replaceCid(url, trialCid);
      dynamicCids.push({ label: `AUTO-${i}`, cid: trialCid, url: trialUrl });
    }
    const allLinks = [...links, ...dynamicCids];

    /* ③ 비동기로 가격 수집 */
    const results = await Promise.allSettled(
      allLinks.map(async link => {
        const { roomRate } = await fetchHotelInfo(link.url);
        return { ...link, price: roomRate };
      })
    );

    const priced = results
      .filter(r => r.status === 'fulfilled' && r.value.price != null)
      .map(r => r.value);

    /* ④ 최저가 계산 */
    priced.sort((a, b) => a.price - b.price);
    const cheapest = priced[0] || null;

    /* ⑤ 호텔명은 첫 성공 응답에서 확보 */
    const firstOk = priced[0];
    const { hotel } = firstOk ? firstOk : await fetchHotelInfo(url);

    return res.json({ hotel, priced, cheapest });
  } catch (err) {
    console.error(err);
    return res.status(500).json({ message: '데이터 수집 실패' });
  }
});

/* ----------------------- 서버 스타트 ----------------------- */
app.listen(port, () =>
  console.log(`Agoda CID Converter server listening on port ${port}`)
);
