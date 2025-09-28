import * as admin from "firebase-admin";
import { setGlobalOptions } from "firebase-functions/v2";

// 전역 설정 및 앱 초기화
setGlobalOptions({
  region: "asia-northeast3",
  maxInstances: 10,
});
admin.initializeApp();


// 다른 파일에 정의된 함수들 불러오기
import { getWeatherData } from "./onCall/getWeather";

export {
    getWeatherData,
};
