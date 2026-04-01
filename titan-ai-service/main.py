import grpc
from concurrent import futures
import sys
import os
import logging

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Add protos folder to path
sys.path.append(os.path.join(os.path.dirname(__file__), 'protos'))

try:
    import risk_engine_pb2 as pb2
    import risk_engine_pb2_grpc as pb2_grpc
except ImportError:
    import protos.risk_engine_pb2 as pb2
    import protos.risk_engine_pb2_grpc as pb2_grpc

# Import health checking
from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_health.v1.health import HealthServicer


class RiskService(pb2_grpc.RiskEngineServiceServicer):
    def CheckRisk(self, request, context):
        try:
            # Input validation
            if not self._validate_request(request, context):
                return pb2.RiskCheckResponse()
            
            # Process risk calculation
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
        """Validate request parameters"""
        # Validate user_id
        if not request.user_id or request.user_id.strip() == "":
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required and cannot be empty")
            return False
        
        # Validate amount
        if request.amount <= 0:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("amount must be positive")
            return False
        
        # Validate transaction_type (if present in proto)
        # Add more validation as needed
        
        return True
    
    def _calculate_risk(self, request):
        """Calculate risk score based on request parameters"""
        # Simple risk calculation logic
        # In production, this would use ML models
        
        amount = request.amount
        
        # Risk scoring logic
        if amount < 1000:
            risk_score = 10
            risk_level = "LOW"
            action = "ALLOW"
        elif amount < 10000:
            risk_score = 50
            risk_level = "MEDIUM"
            action = "REVIEW"
        else:
            risk_score = 85
            risk_level = "HIGH"
            action = "REVIEW"
        
        return risk_score, risk_level, action


def serve():
    try:
        # Create gRPC server
        server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
        
        # Add RiskService
        pb2_grpc.add_RiskEngineServiceServicer_to_server(RiskService(), server)
        
        # Add Health Check Service
        health_servicer = HealthServicer()
        health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
        
        # Set service as SERVING
        health_servicer.set("RiskEngineService", health_pb2.HealthCheckResponse.SERVING)
        health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)  # Overall health
        
        # Start server on port 50051
        server.add_insecure_port('[::]:50051')
        logger.info("🤖 Titan AI Service is running on port 50051...")
        logger.info("✅ Health check service enabled")
        
        server.start()
        server.wait_for_termination()
        
    except Exception as e:
        logger.error(f"Failed to start server: {e}", exc_info=True)
        raise


if __name__ == '__main__':
    serve()
