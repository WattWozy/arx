import { AnalysisService, AnalysisResult } from '../application/AnalysisService';
import { BaseEntity } from '../domain/Entity';

export class ApiClient {
  constructor(private readonly service: AnalysisService) {}

  async fetchAndAnalyze(id: string): Promise<AnalysisResult> {
    const entity = new BaseEntity(id, new Date());
    const metrics = await this.fetchMetrics(id);
    return this.service.analyze(entity, metrics);
  }

  private async fetchMetrics(id: string): Promise<Record<string, number>> {
    // placeholder — real impl would call the archtelemetry JSON endpoint
    return { instability: 0.4, hotspot: 12, fanIn: 3, fanOut: 2 };
  }
}
