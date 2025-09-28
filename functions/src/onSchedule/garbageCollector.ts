import * as admin from "firebase-admin";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";

const db = admin.firestore();

/**
 * 2시간 이상 지난 오래된 날씨 데이터를 Firestore에서 삭제하는 스케줄 함수
 * 매 시 정각에 동작
 */
export const garbageCollector = onSchedule("0 * * * *", async () => {
  logger.info("[GarbageCollector] 오래된 날씨 데이터 삭제 작업 시작");

  // 1. 현재 KST(한국 표준시)를 기준으로 2시간 전 시간을 계산합니다.
  const now = new Date();
  const kstOffset = 9 * 60 * 60 * 1000;
  const kstNow = new Date(now.getTime() + kstOffset);

  // 2시간 전 시간 계산 (날짜 변경을 자동으로 처리)
  const twoHoursAgo = new Date(kstNow.getTime());
  twoHoursAgo.setHours(twoHoursAgo.getHours() - 2);

  // 2. 2시간 전의 baseDate와 baseTime을 계산합니다.
  const limitDate = twoHoursAgo.toISOString().slice(0, 10).replace(/-/g, "");
  const limitHours = String(twoHoursAgo.getHours()).padStart(2, "0");

  logger.info(`[GarbageCollector] 삭제 기준 시간: ${limitDate} ${limitHours}:00 이전 데이터`);

  try {
    // 3. 삭제할 데이터를 쿼리합니다.
    // baseDate가 기준 날짜보다 작거나,
    // baseDate가 같으면서 baseTime의 시간(HH) 부분이 기준 시간보다 작은 문서를 찾습니다.
    const oldWeatherDataQuery = db.collection("weather")
      .where("baseDate", "<", limitDate);

    const sameDayOldWeatherDataQuery = db.collection("weather")
      .where("baseDate", "==", limitDate)
      .where("baseTime", "<", `${limitHours}00`); // baseTime은 HHmm 형식이므로 HH00과 비교

    // 4. 두 쿼리 결과를 가져와 삭제 작업을 수행합니다.
    const [oldSnapshots, sameDayOldSnapshots] = await Promise.all([
      oldWeatherDataQuery.get(),
      sameDayOldWeatherDataQuery.get()
    ]);

    if (oldSnapshots.empty && sameDayOldSnapshots.empty) {
      logger.log("[GarbageCollector] 삭제할 오래된 데이터가 없습니다.");
      return;
    }

    // Firestore 일괄 쓰기(Batch Write)를 사용하여 여러 문서를 한 번에 삭제합니다.
    const batch = db.batch();
    let deleteCount = 0;

    oldSnapshots.forEach(doc => {
      batch.delete(doc.ref);
      deleteCount++;
    });
    sameDayOldSnapshots.forEach(doc => {
      batch.delete(doc.ref);
      deleteCount++;
    });

    // 5. 일괄 삭제 실행
    await batch.commit();
    logger.log(`[GarbageCollector] 총 ${deleteCount}개의 오래된 날씨 데이터를 성공적으로 삭제했습니다.`);

  } catch (error) {
    logger.error("[GarbageCollector] 날씨 데이터 삭제 중 오류 발생:", error);
    // 에러를 다시 던져서 Cloud Scheduler가 실패로 기록하도록 합니다.
    throw error;
  }
});
