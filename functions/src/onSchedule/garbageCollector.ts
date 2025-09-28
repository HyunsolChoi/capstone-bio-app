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

  // 2시간 전 시간 계산
  const twoHoursAgo = new Date(kstNow.getTime());
  twoHoursAgo.setHours(twoHoursAgo.getHours() - 2);

  // 2. 2시간 전의 baseDate를 계산합니다.
  const limitDate = twoHoursAgo.toISOString().slice(0, 10).replace(/-/g, "");

  try {
    // Firestore 일괄 쓰기(Batch Write)를 준비합니다.
    const batch = db.batch();
    let deleteCount = 0;

    const olderDaysQuery = db.collection("weather").where("baseDate", "<", limitDate);
    const olderDaysSnapshots = await olderDaysQuery.get();

    olderDaysSnapshots.forEach(doc => {
      batch.delete(doc.ref);
      deleteCount++;
    });

    if (deleteCount > 0) {
      logger.log(`[GarbageCollector] ${limitDate} 이전 날짜 데이터 ${deleteCount}건을 삭제 목록에 추가했습니다.`);
    }

    // 기준일과 같은 날짜의 모든 문서를 가져와서 코드 내에서 시간(HH)을 비교합니다.
    const sameDayQuery = db.collection("weather").where("baseDate", "==", limitDate);
    const sameDaySnapshots = await sameDayQuery.get();

    let sameDayDeleteCount = 0;
    const limitHours = String(twoHoursAgo.getHours()).padStart(2, "0");

    sameDaySnapshots.forEach(doc => {
      const data = doc.data();
      // baseTime의 앞 두 글자(시간)가 삭제 기준 시간보다 작으면 삭제 대상
      if (data.baseTime && data.baseTime.substring(0, 2) < limitHours) {
        batch.delete(doc.ref);
        sameDayDeleteCount++;
      }
    });

    if (sameDayDeleteCount > 0) {
      logger.log(`[GarbageCollector] ${limitDate} 당일 데이터 중 ${sameDayDeleteCount}건을 삭제 목록에 추가했습니다.`);
      deleteCount += sameDayDeleteCount;
    }

    if (deleteCount === 0) {
      logger.log("[GarbageCollector] 삭제할 오래된 데이터가 없습니다.");
      return;
    }

    // 4. 일괄 삭제 실행
    await batch.commit();
    logger.log(`[GarbageCollector] 총 ${deleteCount}개의 오래된 날씨 데이터를 성공적으로 삭제했습니다.`);

  } catch (error) {
    logger.error("[GarbageCollector] 날씨 데이터 삭제 중 오류 발생:", error);
    // 에러를 다시 던져서 Cloud Scheduler가 실패로 기록하도록 합니다.
    throw error;
  }
});
