# experiments

- 장애 실험 세트. 스크립트의 인자·출력 형식은 e2e 테스트 KDoc이 기준이다(`e2e/CLAUDE.md`).
- 스크립트는 macOS 기본 bash 3.2에서 돌아야 한다. 연관 배열, `mapfile`, `wait -n`, `${x,,}`를 쓰지 않고, `set -u`에서 빈 배열 `"${a[@]}"`도 쓰지 않는다. 확인은 `/bin/bash -n`과 `/bin/bash <스크립트>`로 한다. 권한에서 `/bin/bash`가 막히면 `docker compose run --rm --no-deps -T -v <레포>/experiments:/check:ro --entrypoint bash feed-service -n /check/<스크립트>`로 구문만 본다(컨테이너 bash는 5라 3.2 전용 제약은 따로 본다).
- jq 함수 이름에 예약어(`label`, `reduce`, `foreach`, `import` 등)를 쓰지 않는다. 컴파일 오류는 그 jq를 실제로 돌릴 때(`run.sh`면 Compose 테스트)에야 드러난다.
- k6 스크립트는 Compose 환경 없이 `docker compose run --rm -T --no-deps k6 inspect --execution-requirements -e SCENARIO=S-01 k6/feed.js`로 파싱과 threshold를 확인한다(`__ENV`는 컨테이너 환경 변수가 아니라 k6의 `-e`로 준다). 함수만 돌려 볼 임시 스크립트는 커밋하지 않는 `results/`에 두면 k6 컨테이너에서 보이고, 확인 뒤 지운다.
- 호스트에는 bash, jq, gzip, docker compose만 있다고 본다. 컨테이너 안의 API(하위 서비스 Actuator, Prometheus)는 `docker compose run k6`나 `docker compose exec -T <서비스> wget|curl`로 부른다.
- `run.sh`의 표준 출력은 마지막 두 줄(`<ID>: <판정>`, `리포트: <레포 루트 기준 경로>/report.md`)뿐이고, 진행 상황과 k6 출력은 표준 오류로 간다. 판정과 값은 결과 폴더의 `report.json`에서 읽는다.
- 부하 중 신호를 받으려면 k6를 백그라운드로 띄우고 `wait`한다. 앞에서 돌리면 bash가 자식이 끝날 때까지 trap을 미룬다. 셸 함수를 `&`로 띄우면 `$!`가 서브셸이라 `docker`를 바로 띄운다.
- 비대화형 bash가 `&`로 띄운 자식은 SIGINT를 무시하고, 그 자식은 SIGINT에 trap을 걸 수도 없다. 다른 스크립트를 부르는 스크립트(`run-all.sh` → `run.sh`)는 받은 신호를 자식에게 TERM으로 넘기고, 자식이 되돌리기를 마칠 때까지 `wait`를 되풀이한다.
