// Firestore와 상호작용하기 위한 Firebase Admin SDK를 가져옵니다.
import * as admin from "firebase-admin";

// v2 스케줄링 함수, 로거, 그리고 전역 옵션 설정 함수를 가져옵니다.
import { onSchedule } from "firebase-functions/v2/scheduler";
import { setGlobalOptions } from "firebase-functions/v2";
import * as logger from "firebase-functions/logger";
import { getFirestore } from "firebase-admin/firestore";
import { defineSecret } from "firebase-functions/params";

// 외부 API에 HTTP 요청을 보내기 위한 라이브러리
import fetch from "node-fetch";

// 1. 환경 변수(비밀) 정의
const serviceKey = defineSecret("WEATHER_SERVICE_KEY");

// 2. 모든 함수에 적용될 전역 옵션 설정 (가장 위로 이동)
// 이 코드를 다른 어떤 초기화 코드보다 먼저 실행하여 배포 타임아웃을 방지합니다.
setGlobalOptions({
  region: "asia-northeast3", // 모든 함수의 기본 리전을 서울로 설정
  maxInstances: 10,        // 최대 인스턴스 수
  secrets: [serviceKey],     // 함수가 serviceKey 비밀에 접근할 수 있도록 설정
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

    // 현재 분(minute)을 확인합니다.
    const currentMinutes = kstNow.getMinutes();

    // 47분 이전에 실행될 경우(0~46분), 기준 시간을 한 시간 전으로 조정
    // 이는 스케줄러가 아닌 다른 이유로 함수가 실행될 경우를 대비한 방어 코드
    if (currentMinutes < 47) {
      // kstNow에서 1시간을 뺍니다.
      kstNow.setHours(kstNow.getHours() - 1);
    }

    // ISO 문자열로 변환합니다. 자정이 막 지난 시점에 시간을 뺐을 경우 날짜가 어제로 바뀌는 것을 처리
    const isoString = kstNow.toISOString();

    const baseDate = isoString.slice(0, 10).replace(/-/g, "");
    const baseTime = `${isoString.slice(11, 13)}30`;

    // 추후 GPS데이터 기반하여 csv로 nx, ny 추출토록 해야함.
    // 기본값은 본사 (완주군 이서면)
    const nx = "61";
    const ny = "89";

    // 공공데이터포털 기상청api 초단기예보 사용
    const url = `http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst?dataType=json&serviceKey=${serviceKey.value()}&numOfRows=60&pageNo=1&base_date=${baseDate}&base_time=${baseTime}&nx=${nx}&ny=${ny}`;

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

      const weatherDocRef = db.collection("weather").doc("current-forecast");

      await weatherDocRef.set({
        data: items,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(),
        baseDate: baseDate,
        baseTime: baseTime,
      });

      logger.log("Firestore에 최신 날씨 데이터 저장 성공!");
    } catch (error) {
      logger.error("스케줄러 실행 중 심각한 오류 발생:", error);
      // 에러를 던져서 Cloud Scheduler가 실패로 인지하고 기본 재시도 정책을 따르도록 합니다.
      throw error;
    }
  }
);
