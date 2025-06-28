// src/main/java/com/example/agoda/controller/ConvertController.java

@PostMapping(value = "/convert", consumes = MediaType.APPLICATION_JSON_VALUE)
public ConvertResponse convert(@RequestBody Map<String,String> body) throws Exception {
    String url = body.get("url");
    if (url == null || !url.contains("agoda.com") || !url.contains("cid=") || url.contains("/search"))
        throw new IllegalArgumentException("유효하지 않은 URL입니다.");

    List<CidEntry> allCids = buildCidList();
    List<LinkInfo> results = new ArrayList<>();
    String hotelName = null;

    for (CidEntry entry : allCids) {
        String modUrl = url.replaceAll("cid=-?\\d+", "cid=" + entry.cid);

        // 1) HTML → API URL 추출
        Document doc = Jsoup.connect(modUrl)
                            .header("Accept-Language","ko-KR")
                            .timeout(10_000)
                            .get();
        Element script = doc.selectFirst("script[data-selenium=script-initparam]");
        if (script == null) continue;
        Matcher m = apiUrlPattern.matcher(script.html());
        if (!m.find()) continue;

        String apiPath = m.group(1).replace("&amp;","&");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://www.agoda.com" + apiPath))
                .header("Accept-Language","ko-KR")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = mapper.readTree(resp.body());

        // ← 수정 시작
        JsonNode roomsNode = root.path("rooms");
        if (!roomsNode.isArray() || roomsNode.size() == 0) {
            // rooms 정보 없으면 다음 CID로
            continue;
        }
        JsonNode firstRoom = roomsNode.get(0);
        JsonNode directPriceNode = firstRoom.path("directPrice");
        JsonNode priceNode = directPriceNode.path("originalPrice");
        if (!priceNode.isNumber()) {
            // 가격 정보 없으면 건너뛰기
            continue;
        }
        double price = priceNode.asDouble();
        // ← 수정 끝

        // 호텔명 추출
        JsonNode infoNode = root.path("hotelInfo").path("name");
        if (hotelName == null && infoNode.isTextual()) {
            hotelName = infoNode.asText();
        }

        results.add(new LinkInfo(entry.label, entry.cid, modUrl, price));
        Thread.sleep(200);  // 과도 요청 방지
    }

    // 최저가 찾기
    results.sort(Comparator.comparingDouble(li -> li.price));
    LinkInfo cheapest = results.isEmpty() ? null : results.get(0);

    return new ConvertResponse(hotelName, results, cheapest, AFFILIATES);
}
