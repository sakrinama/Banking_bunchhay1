import grpc
from concurrent import futures
import sys
import os
import logging

# ─── Logging ────────────────────────────────────────────────────────────────
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# ─── Environment Variables ───────────────────────────────────────────────────
# Port the gRPC server listens on
GRPC_PORT = int(os.environ.get("GRPC_PORT", "50051"))

# Max worker threads for the gRPC thread pool
MAX_WORKERS = int(os.environ.get("MAX_WORKERS", "10"))

# Risk thresholds (configurable without code changes)
RISK_LOW_MAX_AMOUNT    = float(os.environ.get("RISK_LOW_MAX_AMOUNT",    "1000"))   # < this → LOW
RISK_MEDIUM_MAX_AMOUNT = float(os.environ.get("RISK_MEDIUM_MAX_AMOUNT", "10000"))  # < this → MEDIUM, else HIGH

# Risk scores
RISK_SCORE_LOW    = int(os.environ.get("RISK_SCORE_LOW",    "10"))
RISK_SCORE_MEDIUM = int(os.environ.get("RISK_SCORE_MEDIUM", "50"))
RISK_SCORE_HIGH   = int(os.environ.get("RISK_SCORE_HIGH",   "85"))

# ─── Proto imports ───────────────────────────────────────────────────────────
sys.path.append(os.path.join(os.path.dirname(__file__), 'protos'))

try:
    import risk_engine_pb2 as pb2
    import risk_engine_pb2_grpc as pb2_grpc
except ImportError:
    import protos.risk_engine_pb2 as pb2
    import protos.risk_engine_pb2_grpc as pb2_grpc

from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_health.v1.health import HealthServicer


# ─── Service Implementation ───────────────────────────────────────────────────
class RiskService(pb2_grpc.RiskEngineServiceServicer):

    def CheckRisk(self, request, context):
        try:
            if not self._validate_request(request, context):
                return pb2.RiskCheckResponse()

            logger.info(f"📡 Analyzing risk for User: {request.user_id}, Amount: {request.amount}")
            risk_score, risk_level, action = self._calculate_risk(request)

            return pb2.RiskCheckResponse(
                risk_score=risk_score,
                risk_level=risk_level,
                action=action
            )

        except ValueError as e:
            logger.warning(f"Validation error: {e}")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(str(e))
            return pb2.RiskCheckResponse()

        except TypeError as e:
            logger.warning(f"Type error: {e}")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(f"Invalid input type: {str(e)}")
            return pb2.RiskCheckResponse()

        except Exception as e:
            logger.error(f"Unexpected error in CheckRisk: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details("Internal server error")
            return pb2.RiskCheckResponse()

    def _validate_request(self, request, context):
        """Validate incoming request fields."""
        if not request.user_id or request.user_id.strip() == "":
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required and cannot be empty")
            return False

        if request.amount <= 0:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("amount must be positive")
            return False

        return True

    def _calculate_risk(self, request):
        """
        Rule-based risk scoring.
        Thresholds are driven by env vars so they can be tuned at deploy time.
        """
        amount = request.amount

        if amount < RISK_LOW_MAX_AMOUNT:
            return RISK_SCORE_LOW, "LOW", "ALLOW"
        elif amount < RISK_MEDIUM_MAX_AMOUNT:
            return RISK_SCORE_MEDIUM, "MEDIUM", "REVIEW"
        else:
            return RISK_SCORE_HIGH, "HIGH", "REVIEW"


# ─── Server Bootstrap ─────────────────────────────────────────────────────────
def serve():
    try:
        server = grpc.server(futures.ThreadPoolExecutor(max_workers=MAX_WORKERS))

        pb2_grpc.add_RiskEngineServiceServicer_to_server(RiskService(), server)

        health_servicer = HealthServicer()
        health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
        health_servicer.set("RiskEngineService", health_pb2.HealthCheckResponse.SERVING)
        health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)

        listen_addr = f'[::]:{GRPC_PORT}'
        server.add_insecure_port(listen_addr)

        logger.info(f"🤖 Titan AI Service starting on {listen_addr}")
        logger.info(f"   MAX_WORKERS            = {MAX_WORKERS}")
        logger.info(f"   RISK_LOW_MAX_AMOUNT    = {RISK_LOW_MAX_AMOUNT}")
        logger.info(f"   RISK_MEDIUM_MAX_AMOUNT = {RISK_MEDIUM_MAX_AMOUNT}")
        logger.info(f"   RISK_SCORE_LOW/MED/HIGH = {RISK_SCORE_LOW}/{RISK_SCORE_MEDIUM}/{RISK_SCORE_HIGH}")
        logger.info("✅ Health check service enabled")

        server.start()
        server.wait_for_termination()

    except Exception as e:
        logger.error(f"Failed to start server: {e}", exc_info=True)
        raise


if __name__ == '__main__':
    serve()
