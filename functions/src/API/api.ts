import * as admin from "firebase-admin";
import * as express from "express";
import { Request, Response } from "express";
import * as logger from "firebase-functions/logger";

const router = express.Router();

// 로그인 API
router.post("/signin", async (request: Request, response: Response) => {
    //debug
    logger.info("/signin 라우트 핸들러 실행됨");
    logger.info("핸들러에서 받은 request.body:", request.body);
  const db = admin.firestore();
  const { empNum, name } = request.body;
    logger.info(`추출된 데이터: empNum=${empNum}, name=${name}`);  if (!empNum || !name) {
    return response.status(400).json({ error: "사번과 이름은 필수입니다." });
  }

  try {
    const employeeRef = db.collection("employees").doc(empNum);
    const doc = await employeeRef.get();

    if (!doc.exists) {
      return response.status(404).json({ error: "해당 사번이 존재하지 않습니다." });
    }

    const employeeData = doc.data()!; // Non-null assertion
    if (employeeData.Name !== name) {
      return response.status(403).json({ error: "이름이 올바르지 않습니다." });
    }

    const uid = `emp_${empNum}`;
    const customToken = await admin.auth().createCustomToken(uid);

    return response.status(200).json({
      message: "로그인 성공",
      token: customToken,
    });
  } catch (error) {
    console.error("[SignIn] 로그인 처리 중 서버 오류 발생:", error);
    return response.status(500).json({ error: "서버 내부 오류가 발생했습니다." });
  }
});

export default router;
