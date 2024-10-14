pipeline {   
    options {        
        disableConcurrentBuilds()   // 동시에 여러 빌드가 실행되지 않도록 설정
    }   
    agent { 
        label 'dss-plugin-tests'  // 'dss-plugin-tests'라는 라벨을 가진 에이전트를 사용
    }   
    environment {        
        PLUGIN_INTEGRATION_TEST_INSTANCE="$HOME/instance_config.json" // 환경 변수 설정: 플러그인 통합 테스트 인스턴스 설정 파일 경로
        UNIT_TEST_FILES_STATUS_CODE = sh(script: 'ls ./tests/*/unit/test*', returnStatus: true) // 유닛 테스트 파일이 있는지 확인하는 스크립트 실행, 상태 코드를 환경 변수에 저장
        INTEGRATION_TEST_FILES_STATUS_CODE = sh(script: 'ls ./tests/*/integration/test*', returnStatus: true) // 통합 테스트 파일이 있는지 확인하는 스크립트 실행, 상태 코드를 환경 변수에 저장
    }   
    stages {      
        stage('Run Unit Tests') { // 유닛 테스트를 실행하는 스테이지
            when { 
                environment name: 'UNIT_TEST_FILES_STATUS_CODE', value: "0" // 유닛 테스트 파일이 존재할 때만 실행
            }         
            steps {            
                sh 'echo "Running unit tests"' // 유닛 테스트 실행 전 메시지 출력
                catchError(stageResult: 'FAILURE') {            
                    sh """               
                        make unit-tests  // 유닛 테스트 실행
                    """            
                }            
                sh 'echo "Done with unit tests"' // 유닛 테스트 완료 후 메시지 출력
            }      
        }      
        stage('Run Integration Tests') { // 통합 테스트를 실행하는 스테이지
            when { 
                environment name: 'INTEGRATION_TEST_FILES_STATUS_CODE', value: "0" // 통합 테스트 파일이 존재할 때만 실행
            }         
            steps {            
                sh 'echo "Running integration tests"' // 통합 테스트 실행 전 메시지 출력
                catchError(stageResult: 'FAILURE') {            
                    sh """               
                        make integration-tests  // 통합 테스트 실행
                    """            
                }            
                sh 'echo "Done with integration tests"' // 통합 테스트 완료 후 메시지 출력
            }      
        }   
    }   
    post {     
        always {        
            script {           
                allure([ // Allure 보고서 생성 및 설정
                    includeProperties: false,                    
                    jdk: '',                    
                    properties: [],                    
                    reportBuildPolicy: 'ALWAYS',                    
                    results: [[path: 'tests/allure_report']]
                ])            
                def status = currentBuild.currentResult // 현재 빌드의 상태 저장
                sh """ 
                    file_name=\$(echo ${env.JOB_NAME} | tr '/' '-').status // 파일 이름 설정: '/'를 '-'로 대체
                    touch \$file_name // 상태 파일 생성
                    echo \"${env.BUILD_URL};${env.CHANGE_TITLE};${env.CHANGE_AUTHOR};${env.CHANGE_URL};${env.BRANCH_NAME};${status};\" >> \$HOME/daily-statuses/\$file_name // 빌드 상태 정보를 파일에 기록
                """            
                cleanWs() // 작업 공간 정리
            }     
        }   
    }
}
