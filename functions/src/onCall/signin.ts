import * as admin from "firebase-admin";
import { onCall, HttpsError } from "firebase-functions/v2/https";

const db = admin.firestore();

export const loginChecker = onCall({ region: "asia-northeast3" }, async (request) => {
  const { name, empNum } = request.data;

  console.log("클라이언트 입력값:", name, empNum);

  if (!name || !empNum) {
    throw new HttpsError("invalid-argument", "이름과 사번은 필수입니다.");
  }

  try {
    const doc = await db.collection("employees").doc(empNum).get();
    console.log("Firestore 조회:", doc.exists, doc.data());

    if (!doc.exists) {
      throw new HttpsError("not-found", "해당 사번이 존재하지 않습니다.");
    }

    const savedName = doc.get("Name");
    if (savedName !== name) {
      throw new HttpsError("permission-denied", "이름이 올바르지 않습니다.");
    }

    // 부서 정보 1~4(optional) 읽어오기
    const depts = [];
    for (let i = 1; i <= 4; i++) {
      const value = doc.get(`dept${i}`);
      if (value) depts.push(value);
      else break; // 연속 필드만 읽음
    }

    return {
      status: "success",
      empNum,
      name: savedName,
      departments: depts.length > 0 ? depts : "미등록", // 부서 정보 없을 경우 미등록 반환
    };
  } catch (error: any) {
    console.error("loginChecker 에러:", error);
    if (error instanceof HttpsError) throw error;
    throw new HttpsError("internal", "서버 오류 발생: " + error.message);
  }
});
