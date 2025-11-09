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

    // 부서 정보 읽어오기 (배열 형태)
    const depts = doc.get("dept");
    if (!Array.isArray(depts) || depts.length === 0) {
      return {
        status: "success",
        empNum,
        name: savedName,
        departments: "미등록",
      };
    }

    return {
      status: "success",
      empNum,
      name: savedName,
       departments: depts.slice(0, 4), // 부서 정보는 최대 4개까지만 가지므로
    };
  } catch (error: any) {
    console.error("loginChecker 에러:", error);
    if (error instanceof HttpsError) throw error;
    throw new HttpsError("internal", "서버 오류 발생: " + error.message);
  }
});
