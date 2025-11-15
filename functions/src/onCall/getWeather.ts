import * as admin from "firebase-admin";

import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { defineSecret } from "firebase-functions/params";
import fetch from "node-fetch";

const db = admin.firestore();
const serviceKey = defineSecret("WEATHER_SERVICE_KEY");

/**
 * 클라이언트의 요청에 따라 기상청 데이터를 가져오는 함수
 * @param {string} nx - X 좌표
 * @param {string} ny - Y 좌표
 * @returns {Promise<object>} - 가공된 날씨 데이터 또는 에러 메시지
 */
export const getWeatherData = onCall({ secrets: [serviceKey] }, async (request) => {
  // 1. 클라이언트가 보낸 nx, ny 값 받기
  const { nx, ny } = request.data;

  // nx 또는 ny 값이 없으면 에러를 발생시킵니다.
  if (!nx || !ny) {
    logger.error("nx, ny 값이 요청에 포함되지 않았습니다.", request.data);
    throw new HttpsError("invalid-argument", "nx, ny 값은 필수입니다.");
  }

  logger.info(`클라이언트 요청 수신: nx=${nx}, ny=${ny}`);

  // 2. API 요청을 위한 base_date, base_time 계산
  const now = new Date();
  const kstOffset = 9 * 60 * 60 * 1000;
  let kstNow = new Date(now.getTime() + kstOffset);
  const currentMinutes = kstNow.getMinutes();
  let flag = false;

  if (currentMinutes < 31) {
    flag = true;
    kstNow.setHours(kstNow.getHours() - 1);
  }

  const isoString = kstNow.toISOString();
  const baseDate = isoString.slice(0, 10).replace(/-/g, "");
  const hours = String(kstNow.getHours()).padStart(2, '0');
  const originalMinutes = new Date(now.getTime() + kstOffset).getMinutes();
  const minutes = String(Math.floor(originalMinutes / 10) * 10).padStart(2, '0');
  let baseTime = ``;
  if (flag) baseTime = `${hours}59`;
  else baseTime = `${hours}${minutes}`;

  // 3. Firestore 캐시 확인
  const docId = `${nx}-${ny}`;
  const weatherDocRef = db.collection("weather").doc(docId);

  try {
    const docSnap = await weatherDocRef.get();
    if (docSnap.exists) {
      const existingData = docSnap.data();
      if (existingData?.baseDate && existingData?.baseTime) {
        const existingDateTimeStr = `${existingData.baseDate}${existingData.baseTime}`;
        const currentDateTimeStr = `${baseDate}${baseTime}`;

        if (currentDateTimeStr <= existingDateTimeStr) {
          logger.log(`[캐시 반환] Firestore에 최신 데이터가 존재하여 캐시된 데이터를 반환합니다.`);
          // Firestore에 저장된 data 필드를 그대로 반환
          return { status: "success", source: "cache", data: existingData.data };
        }

        const existingDate = new Date(parseInt(existingData.baseDate.substring(0, 4), 10), parseInt(existingData.baseDate.substring(4, 6), 10) - 1, parseInt(existingData.baseDate.substring(6, 8), 10), parseInt(existingData.baseTime.substring(0, 2), 10), parseInt(existingData.baseTime.substring(2, 4), 10));
        const currentDate = new Date(parseInt(baseDate.substring(0, 4), 10), parseInt(baseDate.substring(4, 6), 10) - 1, parseInt(baseDate.substring(6, 8), 10), parseInt(baseTime.substring(0, 2), 10), parseInt(baseTime.substring(2, 4), 10));
        const minutesDifference = (currentDate.getTime() - existingDate.getTime()) / (1000 * 60);

        if (minutesDifference < 10) {
          logger.log(`[캐시 반환] 새로운 데이터 기준 시간이 10분 이내이므로 캐시된 데이터를 반환합니다.`);
          return { status: "success", source: "cache", data: existingData.data };
        }
      }
    }
  } catch (error) {
    logger.warn("Firestore 캐시 확인 중 오류 발생:", error);
    // 캐시 확인 중 에러가 발생해도 API 호출은 시도하도록 넘어감
  }

  // 공공데이터포털 기상청api 초단기예보 사용
  // const url = `http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst?dataType=json&serviceKey=${serviceKey.value()}&numOfRows=60&pageNo=1&base_date=${baseDate}&base_time=${baseTime}&nx=${nx}&ny=${ny}`;

  // 기상청 API 호출 (api 허브 대체)
  const url = `https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getUltraSrtFcst?pageNo=1&numOfRows=60&dataType=JSON&base_date=${baseDate}&base_time=${baseTime}&nx=${nx}&ny=${ny}&authKey=${serviceKey.value()}`;
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

    const items = data.response.body.items.item;
    if (!items || items.length === 0) {
      throw new HttpsError("not-found", "API로부터 유효한 데이터를 받지 못했습니다.");
    }

    // 5. 데이터 가공 및 반환 형식 맞추기
    const targetCategories = new Set(["T1H", "RN1", "REH", "PTY", "WSD", "VEC", "SKY"]);

    // category를 키로, fcstValue를 값으로 하는 객체를 생성
    const processedData = items
      .filter((item: any) => {
        const isTarget = targetCategories.has(item.category);
        const hasValue = item.fcstValue !== undefined && item.fcstValue !== null;

        logger.info(`카테고리 ${item.category}: 타겟=${isTarget}, 값=${item.fcstValue}`);
        return isTarget && hasValue;
      })
      .reduce((acc: any, item: any) => {
        acc[item.category] = item.fcstValue;
        return acc;
      }, {});

    processedData.baseDate = items[0].baseDate;
    processedData.baseTime = items[0].baseTime;

    logger.info(`최종 processedData:`, JSON.stringify(processedData));

    // 6. Firestore에 새로운 데이터 저장
    weatherDocRef.set({
      data: processedData,
      baseDate: baseDate,
      baseTime: baseTime,
      nx: nx,
      ny: ny,
    }).catch(err => logger.error("Firestore 저장 실패:", err));

    logger.log(`API로부터 새로운 데이터를 가져와 반환합니다.`);

    // 7. 클라이언트에 성공 결과 반환
    return { status: "success", source: "api", data: processedData };

  } catch (error) {
    logger.error("getWeatherData 함수 실행 중 오류:", error);
    // HttpsError가 아닌 다른 에러일 경우를 대비해 일반 에러로 변환
    if (error instanceof HttpsError) {
      throw error;
    } else {
      throw new HttpsError("internal", "알 수 없는 서버 오류가 발생했습니다.");
    }
  }
});