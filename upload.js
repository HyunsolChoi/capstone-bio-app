// upload.js
const admin = require("firebase-admin");
const fs = require("fs");
const path = require("path");
const csv = require("csv-parser");

// 1) 서비스 계정 키 불러오기
const serviceAccount = require(path.join(__dirname, "serviceAccountKey.json"));

// 2) Firebase Admin 초기화
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  projectId: serviceAccount.project_id
});

// 3) Firestore 인스턴스
const db = admin.firestore();

// 4) CSV 파일 읽어서 Firestore에 업로드
const results = [];
fs.createReadStream(path.join(__dirname, "JGS_3000_test.csv"))
  .pipe(csv())
  .on("data", (row) => {
    results.push(row);
  })
  .on("end", async () => {
    console.log("✅ CSV 읽기 완료, 업로드 시작...");

    for (const row of results) {
      const id = row["ID"]?.trim() || row["﻿ID"]?.trim(); // BOM문자 대비
      const name = row["Name"]?.trim();

      if (!id || !name) {
        console.warn("⚠️ 건너뜀 (ID/Name 없음):", row);
        continue;
      }

      try {
        // employees 컬렉션 → 문서ID를 사번으로 저장
        await db.collection("employees").doc(id).set({
          Name: name
        });
        console.log(`✅ 업로드 성공: ${id} - ${name}`);
      } catch (e) {
        console.error(`❌ 업로드 실패: ${id}`, e);
      }
    }

    console.log("🎉 모든 업로드 완료!");
  });
