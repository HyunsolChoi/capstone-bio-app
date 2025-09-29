// src/onSchedule/garbageCollector.ts

import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";
// index.ts로부터 공유된 db 인스턴스를 가져옵니다.
import { db } from "../index";

/**
 * 2시간 이상 지난 오래된 날씨 데이터를 Firestore에서 삭제하는 스케줄 함수
 * 매 시 정각에 동작
 */
export const garbageCollector = onSchedule("0 * * * *", async () => {
  // ... 이하 코드는 이전과 동일하게 유지 ...
  logger.info("[GarbageCollector] 오래된 날씨 데이터 삭제 작업 시작");

  try {
    const now = new Date();
    const kstOffset = 9 * 60 * 60 * 1000;
    const kstNow = new Date(now.getTime() + kstOffset);

    const twoHoursAgo = new Date(kstNow);
    twoHoursAgo.setHours(twoHoursAgo.getHours() - 2);

    const limitDate = twoHoursAgo.toISOString().slice(0, 10).replace(/-/g, "");
    const limitHours = String(twoHoursAgo.getHours()).padStart(2, "0");

    logger.info(`[GarbageCollector] 삭제 기준 시간: ${limitDate} ${limitHours}00`);

    const batch = db.batch();
    let totalDeleteCount = 0;

    const allDocsQuery = db.collection("weather");
    const allDocsSnapshots = await allDocsQuery.get();

    if (allDocsSnapshots.empty) {
      logger.error("[GarbageCollector] 'weather' 컬렉션에 문서가 전혀 없습니다. DB 인스턴스나 컬렉션 이름을 다시 확인하세요.");
      return;
    }

    logger.info(`[GarbageCollector] 총 ${allDocsSnapshots.size}개의 문서를 확인합니다.`);

    const limitDateNumber = Number(limitDate);
    const limitHoursNumber = Number(limitHours);

    allDocsSnapshots.forEach(doc => {
      const data = doc.data();

      if (!data.baseDate || !data.baseTime) {
        logger.warn(`[GarbageCollector] 문서 ${doc.id}에 baseDate 또는 baseTime 필드가 없습니다. 건너뜁니다.`);
        return;
      }

      const docDateNumber = Number(data.baseDate);
      const docHoursNumber = Number(data.baseTime.substring(0, 2));

      let isTarget = false;

      if (docDateNumber < limitDateNumber) {
        isTarget = true;
        logger.debug(`[삭제 대상] 문서 ${doc.id}의 날짜(${docDateNumber})가 기준일(${limitDateNumber})보다 이전입니다.`);
      }
      else if (docDateNumber === limitDateNumber && docHoursNumber < limitHoursNumber) {
        isTarget = true;
        logger.debug(`[삭제 대상] 문서 ${doc.id}의 날짜는 같지만 시간(${docHoursNumber})이 기준 시간(${limitHoursNumber})보다 이전입니다.`);
      }

      if (isTarget) {
        batch.delete(doc.ref);
        totalDeleteCount++;
      }
    });

    if (totalDeleteCount === 0) {
      logger.log("[GarbageCollector] 삭제할 오래된 데이터가 없습니다.");
      return;
    }

    await batch.commit();
    logger.log(`[GarbageCollector] 총 ${totalDeleteCount}개의 오래된 날씨 데이터를 성공적으로 삭제했습니다.`);

  } catch (error) {
    logger.error("[GarbageCollector] 날씨 데이터 삭제 중 오류 발생:", error);
    if (error instanceof Error && error.message.includes("requires an index")) {
        logger.error("[GarbageCollector] Firestore 인덱스가 필요합니다. 메시지:", error.message);
    } else {
        throw error;
    }
  }
});
