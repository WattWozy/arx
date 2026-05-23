import { Entity } from '../domain/Entity';

export interface AnalysisResult {
  entity: Entity;
  violations: string[];
  metrics: Record<string, number>;
}

export class AnalysisService {
  private results: AnalysisResult[] = [];

  analyze(entity: Entity, rawMetrics: Record<string, number>): AnalysisResult {
    const violations = this.detectViolations(rawMetrics);
    const result: AnalysisResult = { entity, violations, metrics: rawMetrics };
    this.results.push(result);
    return result;
  }

  getHistory(): AnalysisResult[] {
    return [...this.results];
  }

  private detectViolations(metrics: Record<string, number>): string[] {
    const violations: string[] = [];
    if (metrics['instability'] > 0.8) violations.push('high instability');
    if (metrics['hotspot'] > 100) violations.push('hotspot detected');
    return violations;
  }
}
