import * as admin from "firebase-admin";

import { onSchedule } from "firebase-functions/v2/scheduler";
import { setGlobalOptions } from "firebase-functions/v2";
import * as logger from "firebase-functions/logger";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";

import fetch from "node-fetch";

// 환경 변수 정의
const serviceKey = defineSecret("WEATHER_SERVICE_KEY");

// 모든 함수에 적용될 전역 옵션 설정
// 이 코드를 다른 어떤 초기화 코드보다 먼저 실행하여 배포 타임아웃을 방지합니다.
setGlobalOptions({
  region: "asia-northeast3", // 모든 함수의 기본 리전을 서울로 설정
  maxInstances: 10,        // 최대 인스턴스 수
  secrets: ["WEATHER_SERVICE_KEY"],     // 함수가 serviceKey 비밀에 접근할 수 있도록 설정
});

// Firebase 앱을 기본값으로 초기화합니다.
admin.initializeApp();

// 'kesco-bio-app' 데이터베이스에 대한 참조를 가져옵니다.
const db = getFirestore("kesco-bio-app");

// 3. 스케줄링 함수 정의
export const updateWeatherData = onSchedule(
  {
    // 스케줄: 매시 47분에 실행 (API 데이터가 안정적으로 생성된 후)
    // 기상청 api 제공은 매시 45분
    schedule: "47 * * * *",
    timeoutSeconds: 60,
  },
  async (event) => {
    logger.info("기상청 초단기예보 데이터 업데이트 스케줄러 실행");

    // API 요청에 필요한 base_date와 base_time을 안정적으로 계산합니다.
    const now = new Date();
    const kstOffset = 9 * 60 * 60 * 1000;
    let kstNow = new Date(now.getTime() + kstOffset);

    const currentMinutes = kstNow.getMinutes();

    let flag = false; // 31분 미만으로 시간 조정 여부
    // mm이 31분 미만이면, 기준 시간을 1시간 전으로 조정(아직 api가 제공되지 않았을 것을 고려)
    if (currentMinutes < 31) {
      // kstNow에서 1시간을 뺍니다.
      // new Date() 생성자가 날짜 변경(자정)을 자동으로 처리해줍니다.
      flag = true;
      kstNow.setHours(kstNow.getHours() - 1);
    }

    // baseDate와 baseTime을 조정된 kstNow 기준으로 생성합니다.
    const isoString = kstNow.toISOString();
    const baseDate = isoString.slice(0, 10).replace(/-/g, "");

    const hours = String(kstNow.getHours()).padStart(2, '0');
    const originalMinutes = new Date(now.getTime() + kstOffset).getMinutes();
    const minutes = String(Math.floor(originalMinutes / 10) * 10).padStart(2, '0'); // (분의 1의 자리 무시)
    let baseTime = ``;
    if(flag) baseTime = `${hours}59`; // 조정된 시간일 경우 최대 시간으로 처리
    else baseTime = `${hours}${minutes}`; // 현재 시간으로


    // 추후 GPS데이터 기반하여 csv로 nx, ny 추출토록 해야함.
    // 기본값은 본사 (완주군 이서면)
    const nx = "61";
    const ny = "89";

    // 1. 확인할 문서의 ID를 정의
    const docId = `${nx}-${ny}`;
    const weatherDocRef = db.collection("weather").doc(docId);

    // 2. API 호출 전에 Firestore 데이터 확인
    try {
      const docSnap = await weatherDocRef.get();

      if (docSnap.exists) {
        const existingData = docSnap.data();

        if (existingData?.baseDate && existingData?.baseTime) {
          // Firestore에 저장된 시간과 현재 계산된 시간을 Date 객체로 변환하여 비교
          const existingDateTimeStr = `${existingData.baseDate}${existingData.baseTime}`;
          const currentDateTimeStr = `${baseDate}${baseTime}`;

          // 문자열로 직접 비교하여 현재 시간이 더 과거거나 같으면 업데이트 안 함
          if (currentDateTimeStr <= existingDateTimeStr) {
            logger.log(`[중복 방지] 이미 최신 데이터가 존재합니다.`);
            return; // 함수 종료
          }

          // Firestore에 저장된 시간을 Date 객체로 변환
          const existingYear = parseInt(existingData.baseDate.substring(0, 4), 10);
          const existingMonth = parseInt(existingData.baseDate.substring(4, 6), 10) - 1;
          const existingDay = parseInt(existingData.baseDate.substring(6, 8), 10);
          const existingHours = parseInt(existingData.baseTime.substring(0, 2), 10);
          const existingMinutes = parseInt(existingData.baseTime.substring(2, 4), 10);
          const existingDate = new Date(existingYear, existingMonth, existingDay, existingHours, existingMinutes);

          // 현재 계산된 API 요청 기준 시간을 Date 객체로 변환
          const currentYear = parseInt(baseDate.substring(0, 4), 10);
          const currentMonth = parseInt(baseDate.substring(4, 6), 10) - 1;
          const currentDay = parseInt(baseDate.substring(6, 8), 10);
          const currentHours = parseInt(baseTime.substring(0, 2), 10);
          const currentMinutes = parseInt(baseTime.substring(2, 4), 10);
          const currentDate = new Date(currentYear, currentMonth, currentDay, currentHours, currentMinutes);

          // 두 baseTime 간의 시간 차이를 분으로 계산
          const timeDifference = currentDate.getTime() - existingDate.getTime();
          const minutesDifference = timeDifference / (1000 * 60);

          if (minutesDifference < 10) {
            logger.log(`[갱신 방지] 이미 최신 데이터가 있습니다.`);
            return; // 함수 종료
          }
        }
      }

    } catch (error) {
      logger.warn("Firestore 데이터 확인 중 오류 발생:", error);
    }

    // 공공데이터포털 기상청api 초단기예보 사용
    // const url = `http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst?dataType=json&serviceKey=${serviceKey.value()}&numOfRows=60&pageNo=1&base_date=${baseDate}&base_time=${baseTime}&nx=${nx}&ny=${ny}`;

    // 전산원 화재로 인해 기상청api허브 대체
    const url = `https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getUltraSrtFcst?dataType=json&authKey=${serviceKey.value()}&numOfRows=60&pageNo=1&base_date=${baseDate}&base_time=${baseTime}&nx=${nx}&ny=${ny}`;

    logger.info(`요청 URL: ${url}`);

    try {
      const response = await fetch(url);

      if (!response.ok) {
        throw new Error(`API 요청 실패: ${response.status} ${response.statusText}`);
      }

      const data: any = await response.json();

      const resultCode = data?.response?.header?.resultCode;
      if (resultCode !== "00") {
        throw new Error(`API가 에러를 반환했습니다. 코드: ${resultCode}, 메시지: ${data?.response?.header?.resultMsg}`);
      }

      const items = data.response.body.items.item;
      if (!items || items.length === 0) {
        throw new Error("API로부터 유효한 데이터를 받지 못했습니다.");
      }

      const targetFcstTime = items[0].fcstTime;

      // category 필터링: 필요한 카테고리 목록을 정의합니다.
      const targetCategories = new Set(["T1H", "RN1", "REH", "PTY", "WSD", "VEC", "SKY"]);

      // 모든 필터링 및 필드 선택을 한번에 처리합니다.
      const processedItems = items
          .filter((item: any) =>
            // fcstTime과 category 조건을 모두 만족하는 데이터만 선택
            targetFcstTime.includes(item.fcstTime) && targetCategories.has(item.category)
          )
          .map((item: any) => ({
            // 필요한 4개의 필드만으로 새로운 객체를 만들어 반환
            category: item.category,
            fcstDate: item.fcstDate,
            fcstTime: item.fcstTime,
            fcstValue: item.fcstValue,
          }));

      await weatherDocRef.set({
          data: processedItems,
          updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          baseDate: baseDate,
          baseTime: baseTime,
          nx: nx,
          ny: ny,
      });

      logger.log(`Firestore 저장 성공: baseDate=${baseDate}, baseTime=${baseTime}, nx=${nx}, ny=${ny}`);
    } catch (error) {
      logger.error("스케줄러 실행 중 심각한 오류 발생:", error);
      // 에러를 던져서 Cloud Scheduler가 실패로 인지하고 기본 재시도 정책을 따르도록 합니다.
      throw error;
    }
  }
);
