// Firestore와 상호작용하기 위한 Firebase Admin SDK를 가져옵니다.
import * as admin from "firebase-admin";

// v2 스케줄링 함수, 로거, 그리고 전역 옵션 설정 함수를 가져옵니다.
import { onSchedule } from "firebase-functions/v2/scheduler";
import { setGlobalOptions } from "firebase-functions/v2";
import * as logger from "firebase-functions/logger";
import { getFirestore } from "firebase-admin/firestore"; //

// 외부 API에 HTTP 요청을 보내기 위한 라이브러리
import fetch from "node-fetch";

setGlobalOptions({
  region: "asia-northeast3", // 모든 함수의 기본 리전을 서울로 설정
  maxInstances: 10,        // 최대 인스턴스 수
});


// Firebase 앱을 기본값으로 초기화합니다.
admin.initializeApp();

// 'kesco-bio-app' 데이터베이스에 대한 참조를 가져옵니다.
const db = getFirestore("kesco-bio-app"); // Firestore DB name

// 1. 함수 이름과 스케줄을 정의합니다.
// - 'onSchedule'을 사용하여 스케줄링 함수를 만듭니다.
// - 'schedule' 옵션에 'every 30 minutes'를 지정하여 30분마다 실행되도록 합니다.
export const updateWeatherData = onSchedule(
  {
    schedule: "2,32 * * * *",  // 매 2분 32분 마다 수행
    region: "asia-northeast3", // 서울 리전에서 실행
    timeoutSeconds: 60,      // 타임아웃
  },
  async (event) => {
    logger.info("기상청 단기예보 데이터 업데이트 스케줄러 실행");

    // 2. API 요청에 필요한 base_date와 base_time을 현재 시점 기준으로 계산합니다.
    const now = new Date();
    // 한국 시간(KST, UTC+9)으로 변환합니다.
    const kstOffset = 9 * 60 * 60 * 1000;
    const kstNow = new Date(now.getTime() + kstOffset);

    // YYYYMMDD 형식으로 날짜를 포맷합니다.
    const baseDate = kstNow.toISOString().slice(0, 10).replace(/-/g, "");
    // HHMM 형식으로 시간을 포맷합니다. (API는 30분 단위로 발표되므로, 현재 분을 30으로 맞춤)
    const minutes = kstNow.getMinutes();
    const baseTime = `${kstNow.toISOString().slice(11, 13)}${minutes < 30 ? "00" : "30"}`;

    // 업로드 시 제거 필수
    const serviceKey = "f2RibehMZS9OqPqnSEcRB9dc23TvnI8mwGXAKudmNl21texlPzfRlMRUklG4N5gbFPtIp98n0Ycmv0GoJs24OA==";
    const nx = "55"; // 예시 좌표 (서울)
    const ny = "127"; // 예시 좌표 (서울)

    const url = `http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst?dataType=json&serviceKey=${serviceKey}&numOfRows=60&pageNo=1&base_date=${baseDate}&base_time=${baseTime}&nx=${nx}&ny=${ny}`;

    logger.info(`요청 URL: ${url}`);

    try {
      // 3. API 호출 및 재시도 로직
      const response = await fetch(url);

      if (!response.ok) {
        // HTTP 상태 코드가 200-299 범위가 아닐 경우 에러를 발생시킵니다.
        // 이 에러는 Firebase의 자동 재시도 기능을 트리거할 수 있습니다.
        throw new Error(`API 요청 실패: ${response.status} ${response.statusText}`);
      }

      const data: any = await response.json();

      // API 응답 구조에 따라 에러 처리
      const resultCode = data?.response?.header?.resultCode;
      if (resultCode !== "00") {
          throw new Error(`API가 에러를 반환했습니다. 코드: ${resultCode}, 메시지: ${data?.response?.header?.resultMsg}`);
      }

      const items = data.response.body.items.item;
      if (!items || items.length === 0) {
        throw new Error("API로부터 유효한 데이터를 받지 못했습니다.");
      }

      // 4. Firestore에 데이터 저장 (기존 데이터 삭제 후 갱신)
      // 문서를 특정 ID로 고정하여 덮어쓰기(업데이트) 효과를 냅니다.
      // 예: 'current-weather'라는 문서에 항상 최신 날씨를 저장
      const weatherDocRef = db.collection("weather").doc("current-forecast");

      await weatherDocRef.set({
        data: items,
        updatedAt: admin.firestore.FieldValue.serverTimestamp(), // 서버 시간 기준 업데이트 시각 기록
        baseDate: baseDate,
        baseTime: baseTime,
      });

      logger.log("Firestore에 최신 날씨 데이터 저장 성공!");
    } catch (error)
    {
      logger.error("스케줄러 실행 중 심각한 오류 발생:", error);
      // catch 블록에서 에러를 다시 던지면, Firebase가 재시도를 시도합니다.
      throw error;
    }
  }
);