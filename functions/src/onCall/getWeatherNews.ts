import * as admin from "firebase-admin";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { defineSecret } from "firebase-functions/params";
import fetch from "node-fetch";

const db = admin.firestore();
const weatherAlertKey = defineSecret("WEATHER_ALERT_KEY");

export const getWeatherNews = onCall({ secrets: [weatherAlertKey] }, async (request) => {
  const { STN_ID } = request.data;

  if (!STN_ID) {
    logger.error("STN_ID 값이 요청에 포함되지 않았습니다.", request.data);
    throw new HttpsError("invalid-argument", "STN_ID 값은 필수입니다.");
  }

  logger.info(`클라이언트 요청 수신: STN_ID=${STN_ID}`);

  const now = new Date();
  const kstOffset = 9 * 60 * 60 * 1000;
  const kstNow = new Date(now.getTime() + kstOffset);

  const year = kstNow.getFullYear();
  const month = String(kstNow.getMonth() + 1).padStart(2, '0');
  const day = String(kstNow.getDate()).padStart(2, '0');
  const hours = String(kstNow.getHours()).padStart(2, '0');
  const minutes = String(Math.floor(kstNow.getMinutes() / 10) * 10).padStart(2, '0');

  const currentUpdateTime = `${year}${month}${day}${hours}${minutes}`;
  const currentDate = `${year}${month}${day}`;

  const docId = String(STN_ID);
  const weatherNewsDocRef = db.collection("weatherNews").doc(docId);

  try {
    const docSnap = await weatherNewsDocRef.get();
    if (docSnap.exists) {
      const existingData = docSnap.data();
      if (existingData?.lastUpdateTime) {
        const existingDate = existingData.lastUpdateTime.substring(0, 8);
        if (existingDate !== currentDate) {
          logger.info(`[문서 삭제] 날짜가 다름, 기존: ${existingDate}, 현재: ${currentDate}`);
          await weatherNewsDocRef.delete();
        } else if (currentUpdateTime <= existingData.lastUpdateTime) {
          logger.info(`[캐시 반환] 최신 데이터 존재, STN_ID=${STN_ID}, lastUpdateTime=${existingData.lastUpdateTime}`);
          return { status: "success", source: "cache", data: existingData.data };
        }
      }
    }
  } catch (error) {
    logger.warn("Firestore 캐시 확인 중 오류 발생:", error);
  }

  const url = `http://apis.data.go.kr/1360000/WthrWrnInfoService/getWthrInfo?serviceKey=${weatherAlertKey.value()}&numOfRows=20&pageNo=1&stnId=${STN_ID}&dataType=JSON`;
  logger.info(`API 요청 URL: ${url}`);

  try {
    const response = await fetch(url);
    if (!response.ok) {
      throw new HttpsError("unavailable", `API 요청 실패: ${response.status}`);
    }

    const data: any = await response.json();

    const resultCode = data?.response?.header?.resultCode;
    if (resultCode !== "00") {
      throw new HttpsError("internal", `API 에러: ${data?.response?.header?.resultMsg}`);
    }

    const items = data?.response?.body?.items?.item;
    if (!items) {
      throw new HttpsError("not-found", "API로부터 유효한 데이터를 받지 못했습니다.");
    }

    const itemArray = Array.isArray(items) ? items : [items];

    const processedData = itemArray.map(item => ({
      text: item.t1,
      date: item.tmFc
    }));

    logger.info(`API로부터 새로운 데이터 수신: ${processedData.length}건`);

    await weatherNewsDocRef.set({
      data: processedData,
      lastUpdateTime: currentUpdateTime
    });

    logger.info(`Firestore 저장 완료: STN_ID=${STN_ID}, updateTime=${currentUpdateTime}`);

    return { status: "success", source: "api", data: processedData };

  } catch (error) {
    logger.error("getWeatherNews 함수 실행 중 오류:", error);
    if (error instanceof HttpsError) {
      throw error;
    } else {
      throw new HttpsError("internal", "알 수 없는 서버 오류가 발생했습니다.");
    }
  }
});