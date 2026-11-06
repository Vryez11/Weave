# docker

- 루트 `compose.yaml`이 쓰는 설정을 이름별 폴더(`alloy/`, `loki/`, `tempo/`, `prometheus/`, `grafana/`)에 둔다. 서비스 이미지는 `service.Dockerfile` 하나로 빌드한다.
- 이미지마다 헬스 체크에 쓸 수 있는 도구가 다르다. 서비스 이미지는 curl(Dockerfile에서 설치), Tempo·Prometheus·Grafana는 wget이 있다. Loki·Alloy에는 셸과 HTTP 클라이언트가 없어 헬스 체크를 두지 않는다. Toxiproxy는 scratch 이미지라 셸이 없고, 헬스 체크는 `/toxiproxy-cli list`로 한다.
- Toxiproxy 프록시 이름은 의존성 이름(`post` 등)이고 `toxiproxy/toxiproxy.json`에서 띄울 때 불러온다. feed-service는 Toxiproxy 컨테이너의 네트워크 별칭 `<의존성>-proxy:1808x`로 부른다. 관리 API는 호스트 `127.0.0.1:8474`.
- 자원 상한은 서비스마다 `deploy.resources.limits`로 건다(앵커 `x-downstream-deploy`, `x-observability-deploy`, `x-observability-store-deploy`). 상한의 합(메모리 약 9GB)이 Docker에 준 메모리보다 클 수 있다. 상한이지 예약이 아니다.
- k6는 `profiles: [experiment]`라 `up`으로 뜨지 않는다. `docker compose run --rm k6 <인자>`로 부르고(이미지 엔트리포인트가 `k6`), 작업 폴더는 마운트한 `/experiments`다.
- 컨테이너 안에서 관찰 인프라의 API를 부를 때는 `docker compose exec grafana wget -qO- http://<서비스>:<포트>/...`를 쓴다(Loki 3100, Tempo 3200, Alloy 12345, Prometheus 9090).
- Grafana 대시보드는 `grafana/dashboards/*.json`이 기준이다(공급자 `grafana/provisioning/dashboards/`, 화면 수정은 저장 안 됨). 패널 질의에는 시간 변수만 쓰고, 장애가 없을 때도 시계열이 나오게 쓴다. `:e2e:composeTest`의 `ExperimentDashboardComposeScenarioTest`가 모든 질의를 Prometheus에 보내 본다.
- Grafana provisioning 파일에서는 `$`가 환경 변수로 풀린다. Grafana 변수(`${__value.raw}` 등)는 `$$`로 적는다.
- Alloy의 `discovery.docker` 기본 주기는 60초라 짧게 둔다(`config.alloy`). 수집이 늦는지는 Alloy 메트릭 `loki_source_docker_target_entries_total`로 본다.
